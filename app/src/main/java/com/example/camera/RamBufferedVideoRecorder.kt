package com.example.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Custom RAM-Buffered 4K Video Recorder engineered to solve Edge 50 Pro 4K+OIS/EIS storage I/O stalls.
 *
 * Architecture:
 *  Camera2 Surface -> Hardware MediaCodec (HEVC/H264) -> Lock-Free RAM Ring Queue (128/256/512 MB)
 *      -> Dedicated Async Background MediaMuxer Writer Thread -> MediaStore MP4.
 *
 * The camera/encoder threads NEVER block on flash UFS storage writes!
 */
class RamBufferedVideoRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTelemetry: (usedMb: Float, maxMb: Int, droppedFrames: Int, elapsedSec: Int) -> Unit,
    private val onFinished: (Uri?, String, Int, Int) -> Unit
) {
    private data class EncodedPacket(
        val isVideo: Boolean,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null

    private val packetQueue = ConcurrentLinkedQueue<EncodedPacket>()
    private val queuedBytes = AtomicLong(0L)
    private val droppedFrames = AtomicInteger(0)
    private val isRecording = AtomicBoolean(false)
    private val videoTrackIndex = AtomicInteger(-1)
    private val audioTrackIndex = AtomicInteger(-1)
    private val muxerStarted = AtomicBoolean(false)

    @Volatile
    private var pendingVideoFormat: MediaFormat? = null
    @Volatile
    private var pendingAudioFormat: MediaFormat? = null

    private var videoDrainJob: Job? = null
    private var audioCaptureJob: Job? = null
    private var diskFlusherJob: Job? = null
    private var telemetryJob: Job? = null

    private var outputUri: Uri? = null
    private var tempFile: File? = null
    private var outputFileName: String = ""
    private var maxRamBytes: Long = 256L * 1024L * 1024L
    private var maxRamMb: Int = 256
    private var videoWidth = 3840
    private var videoHeight = 2160
    private var startTimeMs = 0L

    @SuppressLint("MissingPermission")
    fun prepare(
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 30,
        bitrateMbps: Int = 65,
        useHevc: Boolean = true,
        ramBufferMb: Int = 256,
        orientationHint: Int = 90,
        recordAudio: Boolean = true
    ): Surface? {
        cleanup()
        videoWidth = width
        videoHeight = height
        maxRamMb = ramBufferMb.coerceIn(64, 512)
        maxRamBytes = maxRamMb * 1024L * 1024L
        outputFileName = "VID_4K_RAM_${System.currentTimeMillis()}.mp4"

        return try {
            val mime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                }
            }

            val vEncoder = runCatching { MediaCodec.createEncoderByType(mime) }.getOrElse {
                // Fallback to H.264 if HEVC encoder unavailable
                val avcFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                    it.configure(avcFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }
            if (vEncoder.codecInfo.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                vEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            val surface = vEncoder.createInputSurface()
            videoEncoder = vEncoder
            inputSurface = surface

            // Prepare optional AAC Audio
            if (recordAudio) {
                runCatching {
                    val sampleRate = 48000
                    val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    aEncoder.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                    val minBuf = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(8192)
                    val recorder = AudioRecord(
                        MediaRecorder.AudioSource.CAMCORDER,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        audioEncoder = aEncoder
                        audioRecord = recorder
                    } else {
                        recorder.release()
                        aEncoder.release()
                    }
                }
            }

            // Prepare output MediaMuxer target
            val file = File(context.cacheDir, outputFileName)
            tempFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(orientationHint)
            }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare RAM-buffered 4K recorder", e)
            cleanup()
            null
        }
    }

    fun startRecording() {
        val vEnc = videoEncoder ?: return
        if (isRecording.getAndSet(true)) return

        queuedBytes.set(0L)
        droppedFrames.set(0)
        videoTrackIndex.set(-1)
        audioTrackIndex.set(-1)
        muxerStarted.set(false)
        pendingVideoFormat = null
        pendingAudioFormat = null
        startTimeMs = System.currentTimeMillis()

        vEnc.start()
        audioEncoder?.start()
        audioRecord?.startRecording()

        // 1. High-priority Video Encoder -> RAM Queue loop (never touches disk)
        videoDrainJob = scope.launch(Dispatchers.Default) {
            drainEncoderToRam(vEnc, isVideo = true)
        }

        // 2. Audio Capture + AAC Encoder -> RAM Queue loop
        if (audioEncoder != null && audioRecord != null) {
            audioCaptureJob = scope.launch(Dispatchers.Default) {
                pumpAudioAndDrainToRam()
            }
        }

        // 3. Dedicated Background Disk Flusher -> drains RAM queue to MediaMuxer
        diskFlusherJob = scope.launch(Dispatchers.IO) {
            flushRamPacketsToMuxer()
        }

        // 4. Real-time HUD Telemetry
        telemetryJob = scope.launch(Dispatchers.Main) {
            while (isActive && isRecording.get()) {
                val usedMb = queuedBytes.get() / (1024f * 1024f)
                val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000L).toInt()
                onTelemetry(usedMb, maxRamMb, droppedFrames.get(), elapsed)
                delay(150L)
            }
        }
    }

    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return

        scope.launch(Dispatchers.IO) {
            runCatching { videoEncoder?.signalEndOfInputStream() }
            delay(250L)

            videoDrainJob?.cancel()
            audioCaptureJob?.cancel()

            // Wait briefly for RAM buffer to flush completely to MP4
            var waitCount = 0
            while (packetQueue.isNotEmpty() && waitCount < 80) {
                delay(50L)
                waitCount++
            }
            diskFlusherJob?.cancel()
            telemetryJob?.cancel()

            val finalUri = finalizeMuxerAndPublishToMediaStore()
            val drops = droppedFrames.get()
            cleanup()
            onFinished(finalUri, outputFileName, videoWidth, drops)
        }
    }

    private fun drainEncoderToRam(encoder: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (isRecording.get() || packetQueue.size < 4) {
            val outIdx = runCatching { encoder.dequeueOutputBuffer(info, 10_000L) }.getOrElse { break }
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (isVideo) pendingVideoFormat = encoder.outputFormat
                    else pendingAudioFormat = encoder.outputFormat
                }
                outIdx >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)

                        val curBytes = queuedBytes.get()
                        if (curBytes + bytes.size <= maxRamBytes) {
                            packetQueue.offer(
                                EncodedPacket(
                                    isVideo = isVideo,
                                    data = bytes,
                                    presentationTimeUs = info.presentationTimeUs,
                                    flags = info.flags
                                )
                            )
                            queuedBytes.addAndGet(bytes.size.toLong())
                        } else if (isVideo) {
                            droppedFrames.incrementAndGet()
                        }
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                else -> {
                    if (!isRecording.get()) break
                }
            }
        }
    }

    private fun pumpAudioAndDrainToRam() {
        val aRec = audioRecord ?: return
        val aEnc = audioEncoder ?: return
        val pcmBuffer = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        val startNs = System.nanoTime()

        while (isRecording.get()) {
            val read = aRec.read(pcmBuffer, 0, pcmBuffer.size)
            if (read > 0) {
                val inIdx = runCatching { aEnc.dequeueInputBuffer(5_000L) }.getOrElse { -1 }
                if (inIdx >= 0) {
                    val inBuf = aEnc.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(pcmBuffer, 0, read.coerceAtMost(inBuf.capacity()))
                        val ptsUs = (System.nanoTime() - startNs) / 1000L
                        aEnc.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                    }
                }
            }
            // Non-blocking drain of AAC packets into RAM queue
            var outIdx = runCatching { aEnc.dequeueOutputBuffer(info, 0L) }.getOrElse { -1 }
            while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pendingAudioFormat = aEnc.outputFormat
                } else if (outIdx >= 0) {
                    val buf = aEnc.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        if (queuedBytes.get() + bytes.size <= maxRamBytes) {
                            packetQueue.offer(
                                EncodedPacket(
                                    isVideo = false,
                                    data = bytes,
                                    presentationTimeUs = info.presentationTimeUs,
                                    flags = info.flags
                                )
                            )
                            queuedBytes.addAndGet(bytes.size.toLong())
                        }
                    }
                    aEnc.releaseOutputBuffer(outIdx, false)
                }
                outIdx = runCatching { aEnc.dequeueOutputBuffer(info, 0L) }.getOrElse { -1 }
            }
        }
    }

    private suspend fun flushRamPacketsToMuxer() {
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()

        while (isRecording.get() || packetQueue.isNotEmpty()) {
            if (!muxerStarted.get()) {
                val vFmt = pendingVideoFormat
                val aFmt = pendingAudioFormat
                val needAudio = audioEncoder != null
                if (vFmt != null && (!needAudio || aFmt != null)) {
                    videoTrackIndex.set(m.addTrack(vFmt))
                    if (aFmt != null) {
                        audioTrackIndex.set(m.addTrack(aFmt))
                    }
                    m.start()
                    muxerStarted.set(true)
                } else {
                    delay(10L)
                    continue
                }
            }

            val pkt = packetQueue.poll()
            if (pkt != null) {
                queuedBytes.addAndGet(-pkt.data.size.toLong())
                val track = if (pkt.isVideo) videoTrackIndex.get() else audioTrackIndex.get()
                if (track >= 0) {
                    info.set(0, pkt.data.size, pkt.presentationTimeUs, pkt.flags)
                    runCatching {
                        m.writeSampleData(track, ByteBuffer.wrap(pkt.data), info)
                    }
                }
            } else {
                delay(5L)
            }
        }
    }

    private fun finalizeMuxerAndPublishToMediaStore(): Uri? {
        runCatching {
            if (muxerStarted.get()) {
                muxer?.stop()
            }
            muxer?.release()
        }
        muxer = null

        val srcFile = tempFile ?: return null
        if (!srcFile.exists() || srcFile.length() < 1024L) return null

        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/KadrRAW")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }
                srcFile.delete()
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish MP4 to MediaStore", e)
            null
        }
    }

    fun cleanup() {
        isRecording.set(false)
        videoDrainJob?.cancel()
        audioCaptureJob?.cancel()
        diskFlusherJob?.cancel()
        telemetryJob?.cancel()
        packetQueue.clear()
        queuedBytes.set(0L)
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        runCatching { audioEncoder?.stop(); audioEncoder?.release() }
        runCatching { videoEncoder?.stop(); videoEncoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { muxer?.release() }
        audioRecord = null
        audioEncoder = null
        videoEncoder = null
        inputSurface = null
        muxer = null
    }

    companion object {
        private const val TAG = "RamVideoRecorder"
    }
}
