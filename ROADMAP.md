# Audiobooks — Voice-Inspired Roadmap

Features, format support, and engineering learnings we can take from
[PaulWoitaschek/Voice](https://github.com/PaulWoitaschek/Voice) — a minimal,
local-only Android audiobook player that has matured over ~10 years. Voice is
the closest existing thing to what Audiobooks is trying to be: calm,
audiobook-specific, no account, no cloud. This roadmap maps its feature set
against our current app and prioritizes through the Light Phone lens
(calm, minimal, battery-aware, local-only, toolbox tool + companion split).

Triaged 2026-08-17 with the user — sleep timer, bookmarks, volume boost,
silence skipping, and cover art were cut; formats, auto-rewind, and the
learning list below were kept. Formats + auto-rewind both landed the same day
(see WORKLOG 2026-08-17).

Status legend: **Have** (already in Audiobooks) · **Priority** (worth building soon) · **Won't-do** (conflicts with LP3/Audiobooks philosophy, or cut by user decision).

## Feature matrix

| Feature | Voice | Audiobooks | Verdict |
|---|---|---|---|
| Resume position | ✅ | ✅ per-book progress, per-chapter time | **Have** |
| Chapter navigation | ✅ | ✅ embedded chapters (MP3 CHAP, M4B bookmarks) + file-per-chapter folders | **Have** |
| Playback speed | ✅ | ✅ 0.5x–2x, global | **Have** |
| Auto-rewind after pause | ✅ | ✅ 15 s after a >5-min pause, baked in | **Have** (2026-08-17) |
| More formats (M4A/OGG/OPUS…) | ✅ M4B, MP3, M4A, OGG, OGA, OPUS | `.mp3`, `.m4b`, `.m4a`, `.aac`, `.ogg`, `.oga`, `.opus`, `.flac`, `.wav` | **Have** (2026-08-17) |
| Sleep timer with fade-out | ✅ | ❌ | **Won't-do** (cut; fade-out technique kept as a learning) |
| Bookmarks | ✅ bookmark management screen | ❌ | **Won't-do** (paused position already persists — a bookmark UI would duplicate it) |
| Volume boost | ✅ | ❌ | **Won't-do** (cut) |
| Silence skipping | ✅ | ❌ | **Won't-do** (cut — heaviest item, risks sounding glitchy) |
| Cover art in player | ✅ cover feature module | ❌ | **Won't-do** (cut) |
| Shake-to-resume after sleep timer | ✅ | ❌ | **Won't-do** (no gesture model on LP3) |
| Android Auto | ✅ | ❌ | **Won't-do** (no AA on LP3) |
| Homescreen widget | ✅ | ❌ | **Won't-do** (LightOS toolbox has no widgets) |
| Folder picker (add a folder) | ✅ | fixed `Audiobooks/` folder | **Won't-do** (deliberate: fewer taps, no folder UI) |
| Cloud sync / account | ❌ by design | ❌ by design | aligned |

## Priority

Both 2026-08-17 priorities landed the same day — details in WORKLOG 2026-08-17:

### ✅ 1. Format support: M4A, OGG, OGA, OPUS, AAC, FLAC, WAV (2026-08-17)
- `hasSupportedExtension` + the mime mapping in `LocalBookRepository` now accept
  `.mp3`, `.m4b`, `.m4a`, `.aac`, `.ogg`, `.oga`, `.opus`, `.flac`, `.wav` —
  all natively decoded by the platform (API 34) via `LightAudioPlayer` (MediaCodec).
- MediaStore indexes them for free; OPUS/OGG carry Vorbis comments (album/title/
  chapter tags work like MP3); M4A uses the same MP4 chapter-atom parser as M4B.
- Verified on the emulator: AAC/OPUS/M4A/WAV decode + play; a mixed-format
  folder book (OGG + MP3 + OPUS) assembles in order and flows across parts.

### ✅ 2. Auto-rewind after pause (2026-08-17)
- Resuming after a >5-min pause jumps back 15 s so the listener re-orients.
- **Baked in** (no Settings toggle — removed on the same day's LP3 feedback
  round: a toggle for the default-on behavior just added a row). Applied
  tool-side in the detached player; verified on the emulator (clock-shifted).

## Won't-do (deliberate)

- **Sleep timer** — cut by user 2026-08-17. If it ever returns, Voice's fade-out pause is the technique to use (see Learnings).
- **Bookmarks** — paused position already persists per book; a bookmark list would add a screen the story doesn't need.
- **Volume boost** — cut. Also blocked unless the SDK player exposes gain.
- **Silence skipping** — cut. Heaviest item (audio analysis) for the least calm-per-code.
- **Cover art** — cut. Typography carries the library; monochrome device anyway.
- **Android Auto** — no Android Auto on the Light Phone; nothing to build against.
- **Homescreen widget** — LightOS toolbox is a list of tools, not a widget host.
- **Shake-to-resume** — LP3 interaction model is taps/buttons, no gestures.
- **Folder picker / multiple library roots** — fixed `Audiobooks/` folder is a conscious simplicity win (zero library-management UI). Voice needs it because it's a general Android app; we don't.
- **Cloud sync, accounts, streaming** — Voice is local-only by design; so are we. Keep it that way (root AGENTS.md).
- **Per-book speed** — Voice (like us) keeps speed simple; ours is deliberately global. Unchanged.

## Learnings from Voice

1. **Fade-out pause.** Voice's sleep timer ends with an exponential volume ramp over the final ~1–2 s, then pause — the detail that makes the stop feel calm instead of abrupt. No sleep timer right now, but this is the technique to use if one is ever added (or for any future timed stop).
2. **Platform codecs, not bundled codecs.** Voice's whole format story is "whatever Android decodes natively". No ffmpeg, no giant decoder dep — exactly the dependency posture this workspace wants. Our gap is only the allowlist in the scanner.
3. **Robust scanning matters more than clever scanning.** Voice skips corrupted/unplayable files instead of failing or spamming the library. We already have incremental scans; the takeaway is to keep single-file failure contained (already the pattern — just don't let one bad file block a folder).
4. **Minimalism is a shield, not a constraint.** Voice's FAQ literally answers "why isn't feature X in the app" with "only essential settings/UI". Every feature we're tempted to add should survive that question. The matrix above is filtered through it.
5. **Architecture lessons are mostly "don't copy".** Voice's modular build (infrastructure/core/features modules, DI, Room, feature flags, remote config) is right for a many-contributor F-Droid app; our two-module tool+companion split already matches the SDK model and the workspace dependency policy. No Room, no DI framework, no feature flags here — position store is fine as prefs.
6. **Licensing.** Voice is GPLv3. Copying its code or a nontrivial algorithm port drags GPL onto Audiobooks; use it as a spec and reference, implement ideas fresh. (Same reason we don't vendor anything from it.)

## Sources

- Voice repo: https://github.com/PaulWoitaschek/Voice
- Voice docs (features / philosophy): https://voice.woitaschek.de/
- Voice architecture: https://voice.woitaschek.de/architecture/
- Voice FAQ (formats, sleep timer, minimalism): https://voice.woitaschek.de/faq/
