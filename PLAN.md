# PLAN — Embedded chapter metadata (MP3 CHAP / M4B bookmarks)

Status: **implemented 2026-08-13** (single-file books; folder-book files stay
file-per-chapter per the out-of-scope note). Deviations from the proposal:
`GetPlaybackState.Response` does **not** gain `chapterIndex`/`chapterCount` —
the tool derives the current chapter from `GetBooks.Part.chapters` +
`positionMs` (single source of truth, no new state fields); and chapter seeks
reuse the existing `SeekTo` global seek (the tool maps a tapped chapter to its
start offset) instead of a new `SeekToChapter` method — no new RPC surface.

Original proposal:

Goal: parse embedded chapters for
single-file books so chapter navigation, "Chapter N of M" labels, chapter-
scoped time, and auto-play-off boundary pauses work from the file's own
chapters — the same experience folder books get per file today.

## Background — current behavior

- A "chapter" is one physical audio file. Folder books map file → chapter
  (filename order; title from the file's `title` tag, fallback filename).
  Single-file books have **no parts/chapters**: no chapter list, no labels.
- `LocalBookRepository.readMetadata()` reads only TITLE/ARTIST/ALBUM/DURATION
  via `MediaMetadataRetriever`; no chapter parsing exists anywhere.
- Player queue = files (ExoPlayer). `MultiPartTimeline` maps a global position
  to (file index, offset). `SeekToPart(index)` = jump to a file.
- Embedded chapter metadata: MP3 = ID3v2 `CHAP`/`CTOC` frames (start/end ms +
  title); M4B = MP4 `moov/udta/chpl` (Nero: start times in 100 ns units +
  title).

## Design

- Keep the ExoPlayer queue = files. Add an embedded-chapter **layer on top**:
  a chapter = `(partIndex, startOffsetMs, endOffsetMs, title)`.
- `AudiobookPart` gains `chapters: List<EmbeddedChapter>`; single-file books
  with embedded chapters get `parts = [one part]` carrying the chapter list
  (single-file books currently have empty parts — adjust book assembly).
- Navigation: chapter → global seek via the existing global timeline
  (`seekTo(globalPartPosition(partIndex, chapterStart, partDurations))`). No
  change to the file queue or to `MultiPartTimeline`'s file math.

## Steps

1. **Parsers** — pure Kotlin, `:server` test source set, no new dependencies.
   - `Id3v2ChapterParser`: parse ID3v2.3/2.4 (header, frame iteration, extended
     header, unsynchronisation, synchsafe ints); collect `CHAP` frames
     (element ID, start ms, end ms, nested `TIT2` description) + `CTOC`
     (ordering). Return `List<EmbeddedChapter>`.
   - `Mp4ChapterParser`: walk the atom tree; locate `moov` (**may sit at the
     end of the file** — walk top-level atom headers, don't read whole files),
     then `udta` → `chpl` (Nero). Entries: start time (100 ns → ms) + title.
   - Unit tests: hand-built byte fixtures + ffmpeg-generated files (ffmpeg
     muxes chapters into MP3 CHAP frames and MP4 `chpl`).
2. **Model + scan**: add `EmbeddedChapter(title, startMs, endMs)` and
   `AudiobookPart.chapters`; call the parsers per file in
   `LocalBookRepository.scanFresh` (only for single-file `.m4b`/`.mp3`
   candidates, or any file that lacks part-level chapters); assemble
   single-file books with a part when chapters exist.
3. **Shared contract** (additive, `sdk:shared` — the same additive-model
   approach the README documents):
   - `GetBooks.Part` gains `chapters: List<Chapter(title, startMs, endMs)>`
     (default empty — backward compatible).
   - `GetPlaybackState.Response` gains `chapterIndex`/`chapterCount`
     (defaulted). Companion computes the current chapter by finding the
     chapter whose `[start, end)` contains the global position.
4. **Controller**:
   - `seekToChapter(index)`: map to the global chapter-start position and
     reuse `seekTo` (crosses file boundaries already).
   - Auto-play-off boundary pause for **embedded chapter ends within a file**
     (position-based: while playing and `!autoPlayNext`, pause when the file
     position reaches an embedded chapter end — alongside the existing
     queue-advance boundary logic).
   - Report `chapterIndex`/`chapterCount` in state.
5. **Tool UI**:
   - `ChaptersPickerScreen`: list the book's chapters (flattened book-wide,
     titles + durations), falling back to parts when there are no embedded
     chapters; tapping returns a chapter index.
   - `PlayerScreen`: chapter label + `chapterTime()` use embedded chapters
     (fall back to the current part-based math); show the chapters button for
     single-file books **with** embedded chapters (currently gated on
     `partCount > 1`).
6. **Verify**:
   - Parser unit tests green.
   - Emulator/LP3: single-file M4B with chapters → chapters button + list
     appear; "Chapter N of M" tracks; jumps land at the right offset; resume
     lands in the right chapter; auto-play-off pauses at embedded ends.
   - Regression: folder books unchanged; single-file books without chapters
     unchanged (no list button, current scrub behavior).
7. **Docs**: update the README/RELEASE limitation line ("Embedded chapter
   metadata … is not parsed") and the `AGENTS.md` Content & metadata section
   once it lands.

## Out of scope (explicitly deferred)

- Folder-book files that themselves contain embedded chapters (file-per-
  chapter stays for folder books) — can be merged later.
- CTOC hierarchies beyond a flat list; chapter artwork.
- Server-side playback in `com.lightos` (still the additive-method model).

## Risks / notes

- ID3v2.4 `CHAP` end time can be `0xFFFFFFFF` (open-ended) → fall back to the
  next chapter's start, else the file duration.
- MP4 `moov` may be at the file end; parse top-level atoms and read the
  `moov` subtree only.
- Parser cost on every scan: consider caching chapter data if scan latency
  on large M4Bs matters (store in `AudiobookProgressStore` or a scan cache).
- `chpl` timestamps are in 100 ns units → divide by 10 000 for ms.
