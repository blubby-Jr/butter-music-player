# Contributing

Thanks for your interest in Butter Music Player! Bug reports, ideas and pull requests are all welcome.

## Reporting bugs

Open an issue using the **Bug report** template and include:
- device model and Android version,
- app version (Settings or `versionName`),
- audio format of the file involved (MP3, FLAC, …),
- steps to reproduce and what you expected to happen.

Please do **not** attach copyrighted music files; a description of the format and tags is enough.

## Development setup

1. Install Android Studio Koala (2024.1) or newer, JDK 17 and Android SDK 34.
2. Open the latest `VerX.Y.Z` folder (currently `Ver0.2.0`) and let Gradle sync.
3. Run on a device or emulator (Android 8.0+).
4. Command line: `cd Ver0.2.0 && gradle assembleDebug` (Gradle 8.7).

## Code style

- Kotlin, `kotlin.code.style=official`.
- The app deliberately uses plain Android views (no Compose / XML layouts) and no third-party dependencies beyond `androidx.core`. Please keep it that way unless there is a strong reason.
- User-visible text goes through `S.kt` with all three languages: English, Ukrainian, Russian.
- Keep playback, DSP and UI separate: state lives in `Core.kt`, audio in `PlayerEngine.kt` / `Dsp.kt`, UI in `MainActivity.kt` / `Views.kt`.

## Pull requests

1. Fork and create a branch from `main`; work in the latest `VerX.Y.Z` folder (`feature/…` or `fix/…`).
2. Keep PRs focused: one feature or fix per PR.
3. Update `CHANGELOG.md` under **[Unreleased]**.
4. Describe what you changed and how you tested it (device, Android version, formats).

## Scope

Features that download or record content from streaming services will not be accepted (see "Not planned" in the README).
