# Kadr RAW — Zaawansowany Aparat Pro i Laboratorium Obliczeniowe (Android / Motorola Edge 50 Pro)

**Kadr RAW** to natywna, polskojęzyczna aplikacja aparatu Pro dla systemu Android stworzona w **Kotlin + Jetpack Compose + Camera2 API + MediaCodec**, zaprojektowana z myślą o wyciśnięciu maksimum możliwości z wieloobiektywowych smartfonów — ze szczególną optymalizacją dla **Motorola Edge 50 Pro**.

---

## Co rozwiązuje Kadr RAW względem aplikacji systemowej i Photon Camera?

1. **Pełny język polski i nowoczesny interfejs** — ciemny, przejrzysty HUD inspirowany kamerami kinowymi z czytelną typografią techniczną (`Space Grotesk` + `JetBrains Mono`).
2. **Naprawa nagrywania 4K ze stabilizacją OIS/EIS (buforowanie RAM)** — eliminuje spadki klatek i zacięcia spowodowane wąskim gardłem zapisu na pamięć flash UFS.
3. **Odblokowane 50 MP w trybie PRO oraz zapis 16-bit RAW (`.dng`)** — pełna kontrola nad migawką, ISO i balansem bieli także przy wysokiej rozdzielczości matrycy.
4. **Możliwość całkowitego wyłączenia poprawy zdjęć ISP HAL** — tryb *Czysta Matryca (0% poprawy)* wyłącza sprzętowe wyostrzanie i odszumianie producenta.

---

## Szczegółowy opis funkcji i zasada działania

### 1. Odblokowany Tryb 50 MP PRO & Zapis RAW (DNG)
- Silnik `CameraHardwareDiscovery` wykrywa wszystkie fizyczne i logiczne obiektywy (`0.5x` ultraszerokokątny, `1x` główny 50 MP, `3x` teleobiektyw, przedni) oraz odpytuje `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` (API 31+).
- Aktywacja **50 MP PRO** ustawia `CaptureRequest.SENSOR_PIXEL_MODE = SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`, odblokowując pełną rozdzielczość matryc Quad-Bayer przy jednoczesnej pełnej kontroli manualnej.
- Zapis **`.dng`** odbywa się przez `ImageFormat.RAW_SENSOR` oraz `android.hardware.camera2.DngCreator` bezpośrednio do katalogu `DCIM/KadrRAW`.

### 2. Nieograniczona Kontrola Ekspozycji (1/16000 s – 60 s) & Balans Bieli
- **Czas naświetlania**: logarytmiczny suwak i szybkie presety od **1/16000 s** (ultrakrótka migawka) do **60 s** (bardzo długa ekspozycja). Gdy żądany czas przekracza sprzętowy limit HAL matrycy, aplikacja automatycznie przełącza się na **wieloklatkowe składanie długiego czasu (AI Stack)**.
- **Czułość ISO**: pełna manualna regulacja czułości matrycy (`SENSOR_SENSITIVITY`).
- **Balans Bieli (WB)**: predefiniowane sceny oraz płynna regulacja **Temperatury (2000K – 10000K)** i **Odcienia / Tint (-50 .. +50)** przeliczana krzywą Plancka na kanały `RggbChannelVector`.

### 3. Funkcje Eksperymentalne
- **Rozmycie Ruchu w Dzień (Symulator Filtra ND)**:
  - Umożliwia uzyskanie efektu długiego naświetlania (rozmyta woda, chmury, ruch uliczny) w pełnym słońcu bez prześwietlania kadru.
  - Przechwytuje serię od **8 do 64 klatek** na krótkiej migawce i składa je w trybach: *Jedwabna woda / chmury* (średnia czasowa), *Smugi światła* (maksimum luminancji) oraz *Ślad ruchu / Panning*.
- **Super-Rozdzielczość z Drgań Ręki (2x Drizzle Shift-and-Add)**:
  - Wykorzystuje naturalne mikroruchy dłoni (monitorowane żyroskopem) podczas serii **4–12 zdjęć**.
  - Wybiera najostrzejszą klatkę bazową (energia Laplaciana), oblicza sub-pikselowe przesunięcia każdego ujęcia z interpolacją paraboliczną i składa je na siatkę o **2x większej rozdzielczości** z dekonwolucją krawędzi — znacząco poprawiając detal na cyfrowym zoomie oraz teleobiektywie 3x.

### 4. Model Odszumiania AI (Na Urządzeniu)
- Wielowątkowy silnik `AiDenoiseEngine` kalibruje model szumu na podstawie ISO ujęcia:
  - Separuje obraz do przestrzeni percepcyjnej **YCbCr**.
  - Nakłada szeroki filtr **Joint-Bilateral na chrominancję** (usuwa kolorowe plamy szumu nocnego bez rozlewania barw na krawędziach).
  - Nakłada krawędziowy filtr adaptacyjny na luminancję z odzyskiem mikro-detali.
  - W trybie **Ultra Noc / Multi-Frame AI** dodatkowo dopasowuje serię klatek z odrzucaniem duchów ruchu.
- Wbudowane **Laboratorium AI** pozwala porównywać oryginał i wynik suwakiem **Przed / Po** oraz testować odszumianie na zdjęciach z galerii (przez bezuprawnieniowy *Android Photo Picker*).

### 5. Całkowite Wyłączenie Poprawy Zdjęć (ISP Bypass)
- Tryb **Czysta Matryca (0% poprawy)** ustawia w `CaptureRequest`:
  - `EDGE_MODE = EDGE_MODE_OFF`
  - `NOISE_REDUCTION_MODE = NOISE_REDUCTION_MODE_OFF`
  - `HOT_PIXEL_MODE = HOT_PIXEL_MODE_OFF`
  - `TONEMAP_MODE = TONEMAP_MODE_FAST`
  - `CONTROL_SCENE_MODE = DISABLED`

### 6. Nagrywanie 4K + OIS/EIS z Buforowaniem w Pamięci RAM (Edge 50 Pro Fix)
- Zamiast blokującego `MediaRecorder`, klasa `RamBufferedVideoRecorder` tworzy bezpośredni łańcuch:
  `Camera2 Surface -> MediaCodec (HEVC H.265 / H.264) -> Lock-Free RAM Ring Queue (128 / 256 / 512 MB) -> Asynchroniczny MediaMuxer`.
- Koder wideo nigdy nie czeka na zapis pamięci flash UFS, a panel HUD na żywo raportuje zajętość bufora RAM, stan stabilizacji OIS+EIS oraz licznik utraconych klatek.

### 7. Histogram na Żywo, Edycja Ekspozycji Live i Focus Peaking
- **Histogram 64-przedziałowy** (Luminancja lub 3 kanały RGB + procent prześwietleń i głębokich cieni).
- **Edycja Ekspozycji w Czasie Rzeczywistym**: EV, Cienie, Światła, Kontrast, Nasycenie i Zebra renderowane sprzętowo na podglądzie (`RenderEffect` / `ColorMatrix`) oraz wypalane w pliku wynikowym.
- **Manualne Wyostrzanie z Podświetlaniem Krawędzi (Focus Peaking)**: wykrywanie ostrości filtrem Sobela na żywo z wyborem koloru krawędzi, czułości i poziomicą żyroskopową.

---

## Jak zbudować i zainstalować aplikację (APK) na Androidzie?

### Metoda 1: Android Studio (Najprostsza)
1. Pobierz kod jako ZIP lub sklonuj z GitHuba.
2. Otwórz folder projektu w **Android Studio**.
3. Wybierz z górnego menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Po zakończeniu kliknij **locate** w powiadomieniu — znajdziesz plik `app/build/outputs/apk/debug/app-debug.apk`.

### Metoda 2: Wiersz poleceń (Gradle)
W katalogu głównym projektu uruchom:
```bash
./gradlew assembleDebug
```
*(W środowisku bez wrappera użyj `gradle assembleDebug`).*

### Instalacja pliku APK na telefonie (np. Motorola Edge 50 Pro)
1. **Przez kabel USB (ADB):**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. **Bezpośrednio na telefonie:**
   - Skopiuj plik `app-debug.apk` do pamięci telefonu (np. folder *Pobrane*).
   - Otwórz go w aplikacji *Pliki*, zezwól na instalację z nieznanych źródeł i potwierdź instalację.
