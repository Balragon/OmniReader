# OmniReader

A lightweight, **fully offline** document viewer for Android. It's the app that
opens when you tap a file in your Files app — no accounts, no ads, no network.
It just opens documents and lets you read them.

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" width="140" alt="OmniReader icon">
</p>

## Supported formats

| Format | Behavior |
|--------|----------|
| Markdown (`.md`) | Rendered with formatting |
| Text (`.txt`) | Shown as-is |
| HTML (`.html`) | Displayed with JavaScript and network disabled (offline) |
| PDF | Built-in renderer, page scrolling + pinch zoom |
| Images (`.png .jpg .webp .gif`) | Fit-to-screen, pinch zoom, EXIF rotation, animated GIF |
| Word (`.docx`) | Converted to Markdown on the fly; export with "Save as MD" when you want |

## Features

- Adjustable text size (Aa) and per-document reading position memory
- Recent files list (documents opened via the in-app "Open file" picker)
- Gallery-style immersive view: tap to hide the status/navigation bars (images & PDF)
- **Offline only** — the app has no internet permission and never sends your documents anywhere
- View-only: it never modifies your original files (the DOCX "Save as MD" action is the only thing that writes a new file)

## Install (Android)

Distributed by **sideloading** (direct APK install), not via the Play Store.

1. Download `OmniReader-x.y.z.apk` from the [latest release](https://github.com/Balragon/OmniReader/releases/latest) onto your Android device.
2. The first time, allow installing from unknown sources
   (Settings → Apps → Special app access → Install unknown apps → allow your browser/Files app).
3. Tap the downloaded APK to install.
4. From then on, tapping a document in your Files app will offer **OmniReader** in the "open with" list.

- Requirement: **Android 10 (API 29) or newer**.
- Official releases are always signed with the same key, so updates install over previous versions.

## Build from source

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Without a release key (`keystore.properties`), the build falls back to debug
signing automatically. JDK 17 is required.

## Development docs

- Architecture & conventions: [CLAUDE.md](CLAUDE.md)
- Work log, current state, known pitfalls: [docs/HANDOFF.md](docs/HANDOFF.md)
- Roadmap: [docs/ROADMAP.md](docs/ROADMAP.md)

## License

[MIT License](LICENSE) — free to use, modify, and redistribute (keep the copyright notice).
Provided as-is, without warranty.
