# Audiobooks

*A minimalist audiobook player for the Light Phone III.*

Play your local-only audiobooks on the Light Phone III, built using the Light SDK.

## Screenshots

<p align="center">
  <img src="screenshots/library.png" width="32%" alt="Library" />
  <img src="screenshots/player.png" width="32%" alt="Player" />
  <img src="screenshots/chapters.png" width="32%" alt="Chapters" />
  <img src="screenshots/settings.png" width="32%" alt="Settings" />
  <img src="screenshots/speed.png" width="32%" alt="Playback speed" />
</p>

## What it does

- Plays audiobooks stored on the phone. Books stay on your device.
- One audio file = one book. One folder of audio files = one book, played in order (name your files 01, 02, 03… to be safe).
- Chapters: folder books list their files, single-file books use their embedded chapter tags (MP3 chapter frames, M4B bookmarks).
- Remembers where you left off.
- Playback speed from 0.5x to 2x, and an Auto-Play toggle for whether the next chapter starts on its own.
- After a pause longer than 5 minutes, playback jumps back 15 seconds so you re-orient.
- Plays in the background, with a media notification and lockscreen controls.
- Formats: MP3, M4B, M4A, AAC, OGG, OGA, OPUS, FLAC, WAV.

## Getting your books on the phone

1. Connect your Light Phone III to a computer and open shared storage.
2. Make a folder called `Audiobooks` (if it isn't there already).
3. Copy one audio file directly into it (that's a book), or make a folder per book and put its files inside.
4. Open Audiobooks. (Rescan from the settings icon → "Scan Library Now".)

## Limitations

- To remove a book, delete its files on the device — there's no in-app delete yet.

## Building it yourself

Needs JDK 17/21, the Android SDK (API 36), and the Light SDK checked out as a sibling folder at `../light-sdk` (fork: [fenleon/light-sdk](https://github.com/fenleon/light-sdk)).

```bash
./gradlew :app:assembleDebug
```

Release signing: see `RELEASE.md`.

## Privacy

No analytics, no ads, no accounts, no telemetry. Your books never leave the device.

## Legal
Started as a fork of [Bard](https://github.com/sjkornelsen/bard), rebuilt for local-only playback.

Unofficial, independent open-source project — not affiliated with or endorsed by The Light Phone, Inc. Light Phone and Light OS are trademarks of The Light Phone, Inc.

MIT licensed — see [LICENSE](LICENSE). Includes resources from the Light SDK, noted in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
