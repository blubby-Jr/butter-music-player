# Changelog

All notable changes to Butter Music Player are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project follows [Semantic Versioning](https://semver.org/).

Each released version has its own folder in this repository (`Ver0.2.0/`, `Ver0.1.3/`, …).

## [Unreleased]

## [0.2.0] - 2026-11-13

### Added
- Repository documentation: `README` in English, Russian and Ukrainian, `LICENSE` (MIT), `CONTRIBUTING.md` and this changelog.
- GitHub setup: bug report / feature request / pull request templates, Dependabot, and GitHub Actions workflows that build a debug APK on every push and publish a release APK when a `vX.Y.Z` tag is pushed.

### Changed
- The app is renamed from "AIMP Mobile" to **Butter Music Player**.
- Repository restructured: every version lives in its own `VerX.Y.Z` folder, GitHub files live in the repository root.
- `versionName` set to `0.2.0`, `versionCode` to `2`.

## [0.1.3]

### Added
- **Add music** button inside a playlist: pick several files at once or a whole folder (subfolders included).
- **`<AutoName>`** as the default playlist name. Adding a folder to such a playlist renames it after that folder.
- **Disable tracks** in a playlist (like in AIMP): a disabled track (☐) stays in the list but is skipped during playback. "Enable all tracks" is available in the playlist menu.
- **Tag editor** (long-press a track): title, artist, album, year, track number, genre. Writing is supported for MP3 (ID3v2.3 / 2.4) and FLAC (Vorbis comment); cover art and unchanged tags are preserved.
- Support for more audio formats in the library and file picker: MP3, FLAC, OGG/Opus, M4A/AAC, WAV, MKA and everything else Android can decode.
- Files that are not yet in `MediaStore` are sent for indexing when they are added from the picker.
- `WRITE_EXTERNAL_STORAGE` permission (Android 9 and older only), needed for tag editing.

### Changed
- The library scan no longer relies only on `IS_MUSIC`; ringtones, notifications and alarms are excluded.

## [0.1.2]

No changes are recorded: the README of this version is identical to 0.1.1.

## [0.1.1]

### Added
- **Playlists**: create (＋), rename and delete (long-press a tab), add tracks (long-press a track), search in the list.
- **Settings**:
  - language: English (default), auto-detect, Ukrainian, Russian;
  - themes: dark, light, AMOLED, custom colours;
  - sound: hard limiter with ceiling, soft clipper, pre-amp, automatic headroom for EQ boost, mono;
  - visualization: refresh rate 10–90 fps, smoothing;
  - sleep timer.
- **Repeat in the editor**: while the editor is open, the track (or the selected regions) loops seamlessly.
- **Telegram-style player bar**: time, volume, order (normal / reverse / shuffle), repeat (off / list / track), speed 0.5–2× with pitch preserved, close.
- **Visualizers**: bars, oscilloscope, analog VU meters, custom lines (count, thickness, colour, gradient, rainbow, mirror, peaks, rounding). Tap shows cover art (a generated picture if there is none), long-press switches the mode.
- **Multiple regions ("drops")** in the editor: draw a selection on the waveform or mark A/B while listening; edges are draggable; "play selection only".
- **Playlist import**: Spotify CSV (Exportify), SoundCloud export, Google Takeout (YouTube Music), TXT `Artist - Title`, JSON. Imported lists are matched against the music on the device and turned into a playlist; missing tracks are listed, and tapping one opens a search in Spotify / SoundCloud / YouTube Music.
- **Notification with buttons and `MediaSession`** (headset buttons, lock screen controls).
- Audio focus (pause on calls, ducking), pause when headphones are unplugged, restoring the last track and position, EQ presets, fade-out before the sleep timer stops playback.
- Procedurally generated app icon (oscilloscope screen); `tools/generate_icon.py [seed]` regenerates it.

### Changed
- Playlists are now created by the user (version 0.1.0 had three fixed playlists).

## [0.1.0]

### Added
- Playback of local music through a `MediaCodec` → DSP → `AudioTrack` engine, with a foreground playback service.
- **18-band equalizer** (±12 dB) with Bypass and Reset.
- **Effects**: echo, reverb, flanger, chorus, bass, stereo width, speed, tempo, pitch (long-press a name to reset).
- **Editor**: waveform with draggable trim bounds, markers, automation lane in the style of FL Studio (tap — new node, drag — change, long-press — delete) and stamp buttons.
- **Per-track settings** stored as JSON in `files/track_settings/<name>__<fingerprint>.json`; the audio file itself is never modified.
- Three playlists (1 = all music, 2–3 filled via long-press on a track → "Add").
- Visualizer with cover art (tap) and mode switch (long-press).
- Limiter.

### Known limitations
- No crossfade (only full playback with a smooth limiter fade at the end).
- DSD (DSF/DFF) is not supported by the standard Android `MediaCodec`.
- AIMP 4.50 visualizers and skins (Windows DLLs) do not work; the app has its own visualizers.
- The UI position runs ahead of the sound by roughly one buffer length (~150 ms).
- No notification / lock screen controls (`MediaSession`) yet.

[Unreleased]: https://github.com/blubby-Jr/butter-music-player/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/blubby-Jr/butter-music-player/compare/v0.1.3...v0.2.0
[0.1.3]: https://github.com/blubby-Jr/butter-music-player/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/blubby-Jr/butter-music-player/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/blubby-Jr/butter-music-player/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/blubby-Jr/butter-music-player/releases/tag/v0.1.0
