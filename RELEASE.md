# Audiobooks — Release

Audiobooks is a standalone Gradle build at the workspace root (`audiobooks/`, module
`:app`), consuming the Light SDK at `../light-sdk` as an included build.

Release identity: package `com.lightphone.audiobooks`, label "Audiobooks".

> **2026-08-09 — application ID migration.** The package was renamed from
> `com.stan.libbylight` to `com.lightphone.audiobooks` as a deliberate breaking
> change. Android treats it as a new application: previous installs must be
> uninstalled and will **not** upgrade in place, and listening progress does
> not carry over.

## Signing

**Keep one signing key for every future upgrade using this application ID.**
Android refuses to upgrade an app whose signature changed; a key change means
uninstalling and losing all listening progress.

The app currently signs **both** debug and release with the workspace dev
key (same key the SDK tools/emulator use):

- Keystore: `../light-sdk/sdk/keys/lightsdk-dev.jks`, alias `lightsdk-dev`,
  password `android` (dev-only, in-repo — do not use as a production identity).
- Sideloadable, and treated as "Light-signed" by the LightOS emulator.

For a true production release, replace this with a private per-app keystore
stored **outside the repository**, keep the same key for all future upgrades,
and never commit the keystore or its passwords.

## Prerequisites

- The workspace toolchain, from the repo root:
  `source tools/env.sh` (sets JDK 21 + Android SDK).
- Build through the memory-guarded wrapper (`tools/build`); it refuses to run
  below its RAM floor and stops leftover daemons.
- A booted LightOS emulator or a connected Light Phone III for install tests.

## Build

```sh
tools/build --dir audiobooks :app:assembleRelease
```

Outputs:

```text
audiobooks/app/build/outputs/apk/release/app-release.apk
audiobooks/app/build/outputs/apk/debug/app-debug.apk
```

Release note: the release variant recompiles more than debug and has OOM-killed
the Gradle daemon on this 16 GB machine while the emulator was running — stop
the emulator during the build if memory is tight.

## Versioning

`versionCode` / `versionName` are declared in `app/lighttool.toml` (single-APK
build since 0.7.0 — the old `server/build.gradle.kts` `defaultConfig` is gone;
`:server` is now a merged library with no version of its own).
Bump `versionCode` by 1 for every release so devices accept the upgrade.

## Verify

```sh
apksigner verify --verbose --print-certs audiobooks/app/build/outputs/apk/release/app-release.apk
aapt dump badging audiobooks/app/build/outputs/apk/release/app-release.apk | grep -E "package:|versionCode"
shasum -a 256 audiobooks/app/build/outputs/apk/release/app-release.apk
```

## Install

An upgrade installation preserves Audiobooks's app-private listening progress (only
possible with the same signing key):

```sh
adb install -r audiobooks/app/build/outputs/apk/release/app-release.apk
```

A clean installation removes the package and its data — only run this after
explicitly accepting that progress is erased (also required when the previously
installed APK used a different key):

```sh
adb uninstall com.lightphone.audiobooks
adb install audiobooks/app/build/outputs/apk/release/app-release.apk
```

On the emulator, grant the storage permission after a clean install:

```sh
adb shell pm grant com.lightphone.audiobooks android.permission.READ_MEDIA_AUDIO
```

## Tester installation

1. Enable developer/USB debugging on the Light Phone III.
2. Connect the phone to a trusted computer; `adb devices` must show it as `device`.
3. `adb install -r <apk-file>` (uninstall first if a different key was used).
4. Open Audiobooks from the LightOS toolbox — a dev-signed tool shows up there
   once External tools is set to "All tools".
5. Keep the APK private; this is an early build.

## Smoke-test checklist

- Open a book, play, seek ±15 seconds, scrub, and change speed from the panel.
- Open a folder book with several chapter files; confirm playback continues
  across part boundaries, the chapter list can jump to any part, and the
  player's time row is scoped to the current chapter.
- Open a single-file M4B with embedded chapters; confirm the chapters button
  appears, the list shows the book's chapters, jumps land at the right
  offsets, "Chapter N of M" tracks, and Auto-Play off pauses at embedded
  chapter ends.
- Press play, leave the app, and confirm background listening continues
  (notification on `audiobooks_playback`, position advances).
- Press play and check the lockscreen/system media panel: seek bar, play/pause,
  and the ±15-second actions; try a Bluetooth/headset media key.
- Pause and confirm the foreground playback notification disappears.
- Put MP3/M4B files (single files and folders) in `Audiobooks`, scan, and play.
- Restart Audiobooks and confirm progress and the last book restore without autoplay.

## Known limitations

- Multi-file books play in embedded track order (disc/track tags, fallback:
  natural filename order); the player shows "Chapter N of M"
  and tapping it opens a chapter list (titles from embedded metadata).
- Embedded chapters inside a folder book's individual files are not merged —
  folder books stay file-per-chapter; single-file books do read their embedded
  chapters (MP3 chapter tags, M4B bookmarks).
- Release builds are R8-minified (since 0.5.5) and exclude the SDK UI module's
  unused ML Kit QR scanner, so the release APKs are ~6-7 MB; debug APKs are
  still ~77 MB (unminified + scanner included).

## Rollback

Reinstall a previously retained APK signed with the same key. Android normally
blocks a lower version code; for an explicitly approved test-device rollback:

```sh
adb install -r -d <previous-signed.apk>
```

If the downgrade is rejected, a clean reinstall is the fallback, but it erases
app-private data. Never uninstall without tester approval.

## Publishing boundary

Do not create a tag or GitHub Release until explicitly approved. Audiobooks's release
home is this repository; its publishing identity is not yet established.
