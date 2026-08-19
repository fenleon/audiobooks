# Audiobooks

*A minimalist audiobook player for the Light Phone III.*

Audiobooks is an audiobook player built specifically for the Light Phone III. It plays audiobooks stored on your device in a calm, text-first interface inspired by the philosophy of Light OS.

Instead of treating audiobooks as another streaming platform, Audiobooks treats them as books. Your library lives on your device, and the player stays out of the way so you can focus on listening.

There are no recommendations, storefronts, advertisements, social features, or cover art — just your books.

Built with the Light ethos: **stripped back, calm, and intentionally small**. Books load from the device — a single shared folder, no accounts, no cloud. Audiobooks is a **real LightOS tool**: a thin interface built on the Light SDK design system; the on-device library scan and the SDK server run inside the same APK (single-module build since 0.7.0), and playback runs inside the tool on the SDK's detached audio player, so background listening survives the tool closing.

**Heritage:** Audiobooks began as a fork of [Bard](https://github.com/sjkornelsen/bard) by sjkornelsen, rebuilt around local-only playback.

Audiobooks is currently in **beta**. It is suitable for daily use; features and behavior may still evolve before a stable release.

> **Current Status:** Beta
>
> **Current Version:** 0.7.0 (versionCode 21)

> **About the name:** the app is called *Audiobooks* — a plain, descriptive
> name in the Light Phone tool-naming style. Application ID:
> `com.lightphone.audiobooks` (a single APK since 0.7.0 — the old companion
> package `com.lightphone.audiobooks.server` is gone).

---

# Screenshots

The library, player, chapter list, settings, and speed picker on a Light Phone III (light-on-black):

<p align="center">
  <img src="screenshots/library.png" width="32%" alt="Library" />
  <img src="screenshots/player.png" width="32%" alt="Player" />
  <img src="screenshots/chapters.png" width="32%" alt="Chapters" />
  <img src="screenshots/settings.png" width="32%" alt="Settings" />
  <img src="screenshots/speed.png" width="32%" alt="Playback speed" />
</p>

---

# Features

## Local Audiobooks

Audiobooks plays audiobooks stored on your device, without a cloud account or subscription.

### Supported Formats

- MP3, M4B
- M4A, AAC, OGG, OGA, OPUS, FLAC, WAV (all natively decoded by the platform — nothing bundled)

### Features

- Single-file audiobooks
- Multi-file audiobooks organized as folders, played continuously in embedded track order (disc/track tags, fallback: natural filename order)
- Chapter list with per-chapter seek — folder books use their files, single-file books use their embedded chapters (MP3 chapter tags, M4B bookmarks)
- Chapter-scoped time and progress in the player; whole-book percent in the library
- Playback speed (0.5x–2x), with an optional Auto-Play "next chapter" toggle
- Rewind on resume: after a long pause (>5 min), playback jumps back 15 s so you re-orient (baked in — no toggle)
- Persistent listening progress across the entire book
- Resume playback
- Alphabetical library ordering (by book title)
- Background playback with a media notification and lockscreen/system media controls
- In-app volume panel on the hardware volume buttons — including a connected Bluetooth device's volume buttons

Audiobooks scans the shared `Audiobooks` folder at any depth. Individual audio files (any supported format) directly inside `Audiobooks/` are treated as standalone books. Every folder inside `Audiobooks/` is treated as a single audiobook, with all supported audio files inside it played continuously in natural order. Audiobooks never copies books into app-private storage.

---

# Getting Started

## Local Audiobooks

1. Connect your Light Phone III to your computer.
2. Create an `Audiobooks` folder in shared device storage if it does not already exist.
3. Either:
  - copy a single audio file (MP3, M4B, M4A, AAC, OGG, OGA, OPUS, FLAC, WAV) directly into the Audiobooks folder, or
  - create one folder per audiobook and place its audio files inside.

Files play in embedded track order (fallback: natural filename order), so numbering them (01, 02, 03, …) is recommended.

4. In Audiobooks, open the app — the library lists every book found in the folder (tap the settings icon in the library's bottom bar, then "Scan Library Now", to rescan).

Android may request permission to read your audio library. Audiobooks does **not** request broad "All Files" storage access.

---

# Current Limitations

Audiobooks is currently designed for the Light Phone III and Android 13 or newer.

Current limitations include:

- Books are removed by deleting their files on the device — there is no in-app delete UI yet.
- Audiobooks ships as a single APK that hosts its own Light SDK server (the media methods it needs are not yet in the production LightOS server `com.lightos`); the app binds to itself, so no companion is installed.

---

# Architecture

Audiobooks is a native Android application written in Kotlin using Jetpack Compose.

Its architecture is intentionally simple, with separate components responsible for local audiobook discovery, playback, progress persistence, and the interface.

The UI is built on the Light SDK's design system (`sdk:ui`) and playback runs on the SDK's detached `LightAudioPlayer` (ExoPlayer). Audiobooks is a standalone Gradle project that consumes the SDK as an included build — see `settings.gradle.kts`. It is a **real LightOS tool**: the `:app` module is built with the SDK's tool plugin (launched from the LightOS toolbox) and owns playback through the SDK's detached audio service — background listening and the media notification live with the tool. The former `:server` companion is merged into the same APK as an Android library: it contributes the SDK server components (the `LightSdkService` the tool binds to, the media file provider, the consent/permission activities) and runs the library scan, the stores, and the volume/BT methods — the privileged work the tool plugin forbids in the tool's own source. The tool binds to itself (`serverPackage = com.lightphone.audiobooks`), so there is exactly one APK to install.

---

# What the merged server adds beyond the current LightOS SDK

The tool talks to its own server over the SDK binder using **media methods that are additive to the SDK** — they were added to `sdk:shared`'s `LightServiceMethod` and are implemented inside the merged APK. The production LightOS server (`com.lightos`) does **not** implement these yet; the merged server is the reference implementation, and the plan is for Light to ship the same surface so tools can target `com.lightos` directly. The merged server provides:

- **The media RPC surface** — `GetBooks`, `ScanLibrary`, `DeleteBook`, `GetAutoPlayNext`/`SetAutoPlayNext`, `GetPlaybackSpeed`/`SetPlaybackSpeed`, `GetVolumeLevel`, `GetBluetoothConnected`, `WaitForVolumeChange`, and `SaveProgress`.
- **Library scanning** — a recursive, incremental scan of `/sdcard/Audiobooks/` (any depth) into single-file and folder books, with titles and chapter names read from embedded metadata (album/title tags) rather than file names.
- **Media file serving** — a content provider serves the library files to the tool's player.
- **Chapter metadata** — embedded chapters (MP3 CHAP frames, M4B bookmarks) are parsed into the book model, so single-file books get the same chapter navigation folder books get per file.
- **Settings & progress persistence** — the Auto-Play "next chapter" toggle, the global playback speed, listening positions, and library ordering survive restarts (stored server-side, applied tool-side).
- **Bluetooth & volume** — the connected-BT state behind the library's Bluetooth icon, and the volume-change long-poll that makes a Bluetooth device's volume buttons show the in-app volume panel instantly.

---

# Development

## Requirements

- JDK 17 or 21 (the workspace provides both under `tools/`)
- Android SDK (API 36)
- A sibling checkout of the Light SDK at `../light-sdk` (consumed as an included build). The local checkout carries a few additive Audiobooks patches (media methods, the tool→companion activity launcher, token sync — documented in the workspace `AGENTS.md`); the patched tree is mirrored at **https://github.com/fenleon/light-sdk** (fork of `lightphone/light-sdk`, `origin` there), with `upstream` pointing at the original.

## Build

From the workspace root, through the memory-guarded wrapper:

```bash
source tools/env.sh
tools/build --dir audiobooks :app:assembleDebug
```

or directly in this directory:

```bash
./gradlew :app:assembleDebug
```

Release signing instructions are available in `RELEASE.md`.

---

# Privacy & Security

Audiobooks does not include analytics, advertising, telemetry, or user accounts.

Local audiobooks remain on your device.

While listening, Audiobooks runs a foreground service so playback continues when the screen is off or Audiobooks is in the background. The playback notification shows the current book title; your library stays on-device.

---

# Roadmap

Planned improvements include:

- In-app library management (remove books)
- Additional playback refinements
- Performance and stability improvements

---

# Contributing

Contributions, bug reports, feature requests, and suggestions are welcome.

If you encounter a bug, please include:

- Audiobooks version
- Light Phone III software version
- Steps to reproduce
- Expected behavior
- Actual behavior

Before opening an issue, please check whether the problem has already been reported.

---

# Frequently Asked Questions

### Does Audiobooks require an account?

No.

Audiobooks play entirely offline and do not require an account.

---

### Does Audiobooks collect analytics or usage data?

No.

Audiobooks does not include analytics, advertising, telemetry, or user tracking.

---

### Does Audiobooks support offline listening?

Yes.

Local audiobooks are always available offline.

---

# Important

Audiobooks is an independent, unofficial open-source project.

Audiobooks is not affiliated with, endorsed by, sponsored by, or approved by The Light Phone, Inc.

Light Phone and Light OS are trademarks of The Light Phone, Inc.

Other trademarks are the property of their respective owners and are used solely to identify compatibility with third-party products and services.

---

# License

Audiobooks is licensed under the MIT License.

See [LICENSE](LICENSE) for the complete license text.

Audiobooks incorporates selected resources derived from the Light SDK. Applicable notices are included in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

Built with ❤️ for the Light Phone community.
