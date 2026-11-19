package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberPro
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CyanOptic
import com.example.ui.theme.EmeraldStable
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidBlack

@Composable
fun GithubDocsScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("docs_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Powrót do aparatu",
                        tint = TextPrimary
                    )
                }
                Text("Dokumentacja GitHub i APK", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            }

            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(GITHUB_README_TEMPLATE))
                    Toast.makeText(context, "Skopiowano README.md do schowka!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmberPro, contentColor = VoidBlack),
                modifier = Modifier.testTag("copy_readme_button")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Kopiuj README", style = MaterialTheme.typography.labelMedium)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DocSectionCard(
                title = "1. Jak Kadr RAW naprawia aparat Motorola Edge 50 Pro",
                accent = EmeraldStable,
                body = "• Nagrywanie 4K + OIS/EIS bez zacięć: Zamiast synchronicznego MediaRecorder (który blokuje wątek kamery przy zapisie na pamięć UFS), Kadr RAW używa bezpośredniego Surface -> MediaCodec (HEVC/H.264) oraz kolejki pierścieniowej w pamięci RAM (128/256/512 MB). Osobny wątek w tle zapisuje pakiety na dysk przez MediaMuxer.\n" +
                    "• Odblokowane 50 MP w trybie PRO i RAW: Aplikacja odpytuje SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION (Android 12+) oraz ustawia SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION z pełnym dostępem do migawki, ISO i 16-bit DngCreator.\n" +
                    "• Całkowite wyłączenie poprawy ISP: Tryb 'Czysta matryca' wymusza EDGE_MODE_OFF, NOISE_REDUCTION_MODE_OFF i HOT_PIXEL_MODE_OFF — eliminując plastikowe wyostrzanie aplikacji systemowej."
            )

            DocSectionCard(
                title = "2. Funkcje eksperymentalne i obliczeniowe",
                accent = AmberPro,
                body = "• Rozmycie ruchu w dzień (Symulator filtra ND): Zbiera serię 8–64 krótkich klatek przy bezpiecznym naświetleniu w pełnym słońcu i składa je czasowo (Jedwabna woda / Smugi światła / Panning) bez prześwietleń.\n" +
                    "• Super-Rozdzielczość z drgań ręki (2x Drizzle): Wykorzystuje naturalne mikroruchy dłoni (monitorowane żyroskopem) do sub-pikselowego wyrównania i dwukrotnego zwiększenia rozdzielczości na zoomie cyfrowym i teleobiektywie 3x.\n" +
                    "• Nieograniczona migawka (1/16000s – 60s): Poniżej limitu sprzętowego używa krótkich czasów matrycy; powyżej limitu HAL automatycznie przełącza na wieloklatkowe składanie długiej ekspozycji.\n" +
                    "• Model Odszumiania AI: Pracuje w przestrzeni YCbCr (osobna redukcja plam chrominancji oraz zachowujące krawędzie odszumianie luminancji kalibrowane względem ISO).\n" +
                    "• Pełny Balans Bieli (2000K–10000K + Odcień Tint), Focus Peaking (Sobel na żywo) oraz Histogram Luma/RGB w czasie rzeczywistym."
            )

            DocSectionCard(
                title = "3. Jak wygenerować i zainstalować plik APK na telefonie",
                accent = CyanOptic,
                body = "Krok 1 (Eksport z AI Studio): W menu ustawień projektu wybierz 'Download ZIP' lub wypchnij kod bezpośrednio na swoje repozytorium GitHub.\n\n" +
                    "Krok 2 (Budowanie APK):\n" +
                    "  • W Android Studio: Otwórz folder projektu -> Build -> Build Bundle(s) / APK(s) -> Build APK(s).\n" +
                    "  • Lub z terminala w katalogu projektu:\n" +
                    "    ./gradlew assembleDebug   (albo gradle assembleDebug)\n" +
                    "  Gotowy plik znajdziesz w: app/build/outputs/apk/debug/app-debug.apk\n\n" +
                    "Krok 3 (Instalacja na Motorola Edge 50 Pro / Android):\n" +
                    "  • Przez USB (ADB): adb install -r app/build/outputs/apk/debug/app-debug.apk\n" +
                    "  • Lub prześlij plik app-debug.apk na telefon i otwórz go w Menedżerze Plików (włącz 'Instalacja z nieznanych źródeł')."
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DocSectionCard(title: String, accent: androidx.compose.ui.graphics.Color, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = accent)
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

val GITHUB_README_TEMPLATE = """
# Kadr RAW — Zaawansowany Aparat Pro i Laboratorium Obliczeniowe (Android / Motorola Edge 50 Pro)

**Kadr RAW** to w pełni polskojęzyczna, nowoczesna aplikacja fotograficzno-filmowa napisana natywnie w **Kotlin + Jetpack Compose + Camera2 API + MediaCodec**, zaprojektowana z myślą o maksymalnym wykorzystaniu sprzętu wieloobiektywowego — ze szczególną optymalizacją dla **Motorola Edge 50 Pro**.

## Dlaczego powstał Kadr RAW?
Fabryczna aplikacja aparatu często agresywnie wyostrza zdjęcia, uniemożliwia pełne wyłączenie obróbki ISP, blokuje 50 MP w trybie PRO/RAW oraz potrafi gubić klatki podczas nagrywania **4K ze stabilizacją**. Z kolei zagraniczne alternatywy (np. Photon) nie oferują pełnego języka polskiego ani dedykowanego buforowania wideo w pamięci RAM.

## Główne Funkcje

### 1. Odblokowany Tryb 50 MP PRO oraz 16-bit RAW (DNG)
- Wykorzystuje `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` oraz `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` (API 31+), aby odblokować pełną rozdzielczość matryc Quad-Bayer w trybie manualnym.
- Zapisuje standardowe pliki **`.dng`** przez `android.hardware.camera2.DngCreator` (gotowe do obróbki w Adobe Lightroom, RawTherapee, Darktable).

### 2. Nieograniczona Ekspozycja (1/16000s – 60s) & Pełny Balans Bieli
- **Czas naświetlania**: logarytmiczna skala od **1/16000 s** do **60 s**. Gdy zadany czas przekracza sprzętowy limit HAL matrycy, aplikacja automatycznie przełącza się w **wieloklatkowe składanie długiej ekspozycji (AI Stack)**.
- **Czułość ISO**: pełna manualna regulacja w całym zakresie matrycy.
- **Balans Bieli (WB)**: predefiniowane tryby oraz pełna manualna kontrola **Temperatury (2000K–10000K)** i **Odcienia / Tint (-50..+50)** przeliczana równaniem Plancka na wektory `RggbChannelVector`.

### 3. Całkowite Wyłączenie Poprawy Zdjęć (ISP Bypass)
- Tryb **Czysta Matryca (0% poprawy)** jawnie wyłącza `EDGE_MODE_OFF`, `NOISE_REDUCTION_MODE_OFF`, `HOT_PIXEL_MODE_OFF` oraz profile sceny w strumieniu Camera2 — gwarantując czysty obraz bez halowego przeostrzenia.

### 4. Funkcje Eksperymentalne
- **Rozmycie Ruchu w Dzień (Symulator Filtra ND)**: Składa serię 8–64 klatek wykonanych na krótkiej migawce w pełnym słońcu (tryby: *Jedwabna woda/chmury*, *Smugi światła*, *Panning*) bez przepalania świateł.
- **Super-Rozdzielczość z Drgań Ręki (2x Drizzle Shift-and-Add)**: Wykorzystuje naturalne mikroruchy dłoni (monitorowane przez żyroskop) do sub-pikselowego dopasowania serii 4–12 zdjęć i rekonstrukcji dwukrotnie wyższej rozdzielczości na zoomie cyfrowym i teleobiektywie 3x.

### 5. Model Odszumiania AI + Laboratorium Edycji
- Wielowątkowy silnik odszumiania w przestrzeni **YCbCr** kalibrowany pod ISO ujęcia: szeroka filtracja *Joint-Bilateral* usuwa szum kolorowy (chrominancję), a adaptacyjny filtr krawędziowy wygładza ziarno luminancji z zachowaniem mikro-detali.
- Tryb **Ultra Noc / Multi-Frame AI** dodatkowo dopasowuje i uśrednia serię klatek metodą odporną na duchy ruchu.
- Wbudowane **Laboratorium AI** oferuje interaktywny podgląd **Przed / Po** oraz suwaki korekcji ekspozycji.

### 6. Nagrywanie 4K + OIS/EIS Buforowane Bezpośrednio w Pamięci RAM (Edge 50 Pro Fix)
- Standardowy `MediaRecorder` blokuje zapis na pamięci flash UFS przy wysokim bitrate 4K z aktywną stabilizacją OIS/EIS.
- **Kadr RAW** stosuje architekturę: `Camera2 Surface -> MediaCodec (HEVC/H.264) -> Lock-Free RAM Ring Queue (128/256/512 MB) -> Asynchroniczny MediaMuxer`.
- Wątek kodera wideo nigdy nie czeka na zapis dyskowy, eliminując zacięcia i gubienie klatek.

### 7. Podgląd Pro: Histogram Luma/RGB, Edycja Live i Focus Peaking
- **Histogram w czasie rzeczywistym** (Luminancja lub 3 kanały RGB + alarm prześwietleń/cieni).
- **Focus Peaking**: wykrywanie krawędzi filtrem Sobela na żywo z wyborem koloru i czułości oraz poziomicą żyroskopową.
- **Edycja parametrów ekspozycji w czasie rzeczywistym**: suwaki EV, Cienie, Światła, Kontrast, Nasycenie i Zebra renderowane sprzętowo przez `RenderEffect` / `ColorMatrix` na podglądzie i wypalane do pliku wyjściowego.

## Jak zbudować i zainstalować plik APK?
1. Sklonuj repozytorium i otwórz w **Android Studio** (lub użyj Gradle CLI).
2. Zbuduj pakiet debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. Zainstaluj na telefonie przez ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   lub skopiuj plik `app/build/outputs/apk/debug/app-debug.apk` bezpośrednio do pamięci telefonu i otwórz go w menedżerze plików.
""".trimIndent()
