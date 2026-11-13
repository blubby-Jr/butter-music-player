<div align="center">

<img src="Ver0.2.0/tools/icon_preview.png" width="128" alt="Butter Music Player icon">

# Butter Music Player

**A feature-rich offline music player for Android — playlists, per-track editor with multiple loop regions, 18-band EQ, effects, visualizers and a built-in tag editor.**

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md)

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![Version](https://img.shields.io/badge/version-0.2.0-orange)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

> **Disclaimer.** This is an independent hobby project. It is **not affiliated with, endorsed by or derived from AIMP** (by Artem Izmaylov) or any streaming service mentioned below. All trademarks belong to their respective owners.

## Features

### Library & playlists
- Scans your device music through `MediaStore` — **MP3, FLAC, OGG/Opus, M4A/AAC, WAV, MKA** and everything else Android can decode.
- **Playlists**: create (＋), rename / delete (long-press a tab), add tracks (long-press a track), search.
- **Add music** button inside a playlist: pick **several files** or a **whole folder** (subfolders included).
- **`<AutoName>`**: the default name of a new playlist. Add a folder to it and the playlist is renamed after that folder.
- **Disable tracks** like in AIMP: a disabled track (☐) stays in the playlist but is skipped during playback.
- **Import playlists** from Spotify (Exportify CSV), SoundCloud export, Google Takeout (YouTube Music), plain `Artist - Title` TXT or JSON — matched against the music on your device. Missing tracks are listed, and tapping one opens a search in Spotify / SoundCloud / YouTube Music.

### Tag editor
- Title, artist, album, year, track number, genre.
- Writes **MP3 (ID3v2.3 / 2.4)** and **FLAC (Vorbis comment)**. Cover art and all other tags are preserved; only the fields you changed are touched.
- Other formats are playable but read-only.

### Sound
- 18-band equalizer with presets, effects chain, automation lanes.
- Hard limiter with ceiling, soft clipper, pre-amp, automatic headroom for EQ boost, mono mode.
- Playback speed 0.5–2× with pitch preserved.
- Sleep timer with a 10-second fade-out.

### Editor
- Waveform view, **multiple selection regions ("drops")** drawn on the waveform or marked A/B while listening; edges are draggable; "play selection only".
- Seamless looping of the track or the selected regions while the editor is open.
- Per-track settings are stored as JSON.

### Visualizers
Bars · oscilloscope · analog VU meters · custom lines (count, thickness, colour, gradient, rainbow, mirror, peaks, rounding). Tap the visualizer for cover art, long-press for the next mode.

### Everything else
- Themes: dark, light, AMOLED, custom colours. Languages: English, Українська, Русский (or follow the device).
- Audio focus (pause on calls, ducking), pause when headphones are unplugged, notification with controls, `MediaSession` (headset buttons).
- Restores the last track and position on launch.

## Privacy

- **No internet permission**, no analytics, no ads. Everything stays on your device.
- Permissions: audio access (`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`), notifications and a foreground service for playback, and `WRITE_EXTERNAL_STORAGE` on Android 9 and older (tag editing only). On Android 10+ the system asks for confirmation before a file's tags are changed.

## Repository layout

```
BMS/
├── Ver0.2.0/        ← current version (Android Studio project)
├── Ver0.1.3/        ← archived version
├── Ver0.1.2/        ← archived version
├── Ver0.1.1/        ← archived version
├── Ver0.1.0/        ← archived version
├── .github/         ← CI workflows, issue / PR templates
├── CHANGELOG.md
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

Each `VerX.Y.Z` folder is a self-contained project: open **that** folder in Android Studio. Source files are in `Ver0.2.0/app/src/main/java/com/example/aimpmobile/`.

## Build

Requirements: Android Studio Koala (2024.1) or newer, JDK 17, Android SDK 34.

```bash
git clone https://github.com/blubby-Jr/butter-music-player.git
```

1. Open the `Ver0.2.0` folder in Android Studio (**File ▸ Open**) and wait for Gradle sync.
2. Run on a device (Android 8.0+, USB debugging) or an emulator.
3. APK: **Build ▸ Build APK(s)** → `app/build/outputs/apk/debug/`.

Command line (Gradle 8.7):

```bash
cd Ver0.2.0
gradle assembleDebug
```

> The Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) is not committed. Run `gradle wrapper --gradle-version 8.7` once (or let Android Studio generate it) if you prefer `./gradlew`.

## Project structure

| File | Purpose |
| --- | --- |
| `Core.kt` | App state, playback queue, audio focus, sleep timer |
| `PlayerEngine.kt` | `MediaCodec` → DSP → `AudioTrack` |
| `Dsp.kt`, `Fft.kt` | EQ, effects, limiter, spectrum |
| `Selection.kt`, `Automation.kt`, `TrackSettings.kt` | Regions, automation, per-track JSON |
| `Library.kt` | `MediaStore` scan, playlists (with disabled tracks) |
| `PickImport.kt` | Matching picked files / folders to library tracks |
| `TagEditor.kt` | ID3v2 and Vorbis-comment writer |
| `ImportParser.kt` | Playlist import parsers |
| `Views.kt`, `MainActivity.kt`, `Theme.kt`, `S.kt`, `AppSettings.kt` | UI, theming, localisation, settings |
| `PlaybackService.kt` | Foreground service, notification, `MediaSession` |
| `tools/generate_icon.py` (in `Ver0.2.0/`) | Procedural icon generator (`python3 tools/generate_icon.py [seed]`, needs Pillow) |

## Not planned

- Downloading or recording tracks from Spotify, SoundCloud or YouTube Music (violates their terms and copyright). Only list import and search links are provided.
- One-click like import (needs account login and developer API keys) — use file export instead.
- Crossfade, DSD, AIMP skins / visualizer plugins (Windows DLLs).

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Release history is in [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE)
