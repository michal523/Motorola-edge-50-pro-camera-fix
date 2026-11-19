package com.example.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.CapturedMediaItem
import com.example.domain.model.IspProcessingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

private val Context.kadrPrefs by preferencesDataStore(name = "kadr_raw_prefs")

class MediaStorageRepository(private val context: Context) {

    private object Keys {
        val ISP_MODE = stringPreferencesKey("isp_mode")
        val RAM_BUFFER_MB = intPreferencesKey("ram_buffer_mb")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val HISTOGRAM_VISIBLE = booleanPreferencesKey("histogram_visible")
        val GRID_ENABLED = booleanPreferencesKey("grid_enabled")
    }

    val savedIspMode: Flow<IspProcessingMode> = context.kadrPrefs.data.map { prefs ->
        val raw = prefs[Keys.ISP_MODE] ?: IspProcessingMode.KADR_COMPUTATIONAL.name
        runCatching { IspProcessingMode.valueOf(raw) }.getOrDefault(IspProcessingMode.KADR_COMPUTATIONAL)
    }

    val savedRamBufferMb: Flow<Int> = context.kadrPrefs.data.map { it[Keys.RAM_BUFFER_MB] ?: 256 }

    suspend fun saveIspMode(mode: IspProcessingMode) {
        context.kadrPrefs.edit { it[Keys.ISP_MODE] = mode.name }
    }

    suspend fun saveRamBufferMb(mb: Int) {
        context.kadrPrefs.edit { it[Keys.RAM_BUFFER_MB] = mb }
    }

    suspend fun saveJpegBitmap(
        bitmap: Bitmap,
        fileNamePrefix: String,
        iso: Int,
        shutterDisplay: String,
        lensLabel: String,
        modeLabel: String,
        denoiseLabel: String
    ): CapturedMediaItem? = withContext(Dispatchers.IO) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        saveJpegBytes(
            bytes = stream.toByteArray(),
            width = bitmap.width,
            height = bitmap.height,
            fileNamePrefix = fileNamePrefix,
            iso = iso,
            shutterDisplay = shutterDisplay,
            lensLabel = lensLabel,
            modeLabel = modeLabel,
            denoiseLabel = denoiseLabel
        )
    }

    suspend fun saveJpegBytes(
        bytes: ByteArray,
        width: Int,
        height: Int,
        fileNamePrefix: String,
        iso: Int,
        shutterDisplay: String,
        lensLabel: String,
        modeLabel: String,
        denoiseLabel: String
    ): CapturedMediaItem? = withContext(Dispatchers.IO) {
        val fileName = "${fileNamePrefix}_${System.currentTimeMillis()}.jpg"
        val uri = writeBytesToPicturesOrDcim(bytes, fileName, "image/jpeg") ?: return@withContext null
        CapturedMediaItem(
            id = UUID.randomUUID().toString(),
            uriString = uri.toString(),
            fileName = fileName,
            isDng = false,
            isVideo = false,
            width = width,
            height = height,
            megapixels = (width * height) / 1_000_000f,
            iso = iso,
            shutterDisplay = shutterDisplay,
            lensLabel = lensLabel,
            modeLabel = modeLabel,
            denoiseLabel = denoiseLabel,
            timestampMs = System.currentTimeMillis()
        )
    }

    suspend fun saveDngBytes(
        dngBytes: ByteArray,
        width: Int,
        height: Int,
        iso: Int,
        shutterDisplay: String,
        lensLabel: String
    ): CapturedMediaItem? = withContext(Dispatchers.IO) {
        val fileName = "RAW_DNG_${System.currentTimeMillis()}.dng"
        val uri = writeBytesToPicturesOrDcim(dngBytes, fileName, "image/x-adobe-dng") ?: return@withContext null
        CapturedMediaItem(
            id = UUID.randomUUID().toString(),
            uriString = uri.toString(),
            fileName = fileName,
            isDng = true,
            isVideo = false,
            width = width,
            height = height,
            megapixels = (width * height) / 1_000_000f,
            iso = iso,
            shutterDisplay = shutterDisplay,
            lensLabel = lensLabel,
            modeLabel = "RAW DNG 16-bit",
            denoiseLabel = "Brak (Czysty Bayer)",
            timestampMs = System.currentTimeMillis()
        )
    }

    suspend fun loadBitmapFromUri(uri: Uri, maxDim: Int = 1800): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            var sample = 1
            while (boundsOpts.outWidth / sample > maxDim || boundsOpts.outHeight / sample > maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeBytesToPicturesOrDcim(bytes: ByteArray, fileName: String, mimeType: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/KadrRAW")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                uri
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DCIM), "KadrRAW")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }
}
