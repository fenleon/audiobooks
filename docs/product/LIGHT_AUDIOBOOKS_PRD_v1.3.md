# Light Audiobooks: Product Requirements Document

**Version:** 1.3  
**Status:** Product behavior approved; engineering feasibility and validation pending  
**Platform:** Light Phone III / LightOS  
**Repository:** https://github.com/sairansorosie/audiobooks  
**Audited starting application:** v0.9.2 (`:tool` Kotlin/Jetpack Compose module)  
**Companion document:** `LIGHT_AUDIOBOOKS_IMPLEMENTATION_PLAYBOOK_v1.0.md`

## 1. Product objective and non-goals

Improve the existing local-first audiobook player without replacing its playback foundation. The user must be able to browse books, see accurate whole-book listening status, edit local metadata, organize series, manage completion, return to the most recently played book, and safely remove a book's files. Work must be delivered **one approved feature at a time** with hands-on QA and explicit permission before beginning the next feature.

**Non-goals:** cloud sync, streaming/catalogs, accounts, cover art, analytics, advertising, automatic internet metadata, new upload mechanisms, audiobook search, author-grouped navigation, custom shelves, Title Z-A, Author Z-A, Series A-Z/Z-A sort selectors, replacing the playback engine, sleep timer, and new bookmarks in this release. Named bookmarks remain a separately scoped future specification; preserve them if subsequently implemented.

**Existing features to preserve:** shared `/sdcard/Audiobooks` and private Tool Manager `files/shared/books` discovery; automatic and manual scans; MP3/M4B/M4A/AAC/OGG/OGA/OPUS/FLAC/WAV; folder-book grouping; embedded/file-per-chapter navigation; play/pause; ±15-second transport seeking; 0.5–2x speed; persistent listening position; auto-play-next setting; existing automatic near-end completion semantics; SDK detached playback; 15-second rewind after a pause exceeding five minutes; Player chapter-scoped scrubber. Do **not** conflate Player's chapter scrubber with Library's whole-book status.

## 2. Navigation and screen hierarchy

```text
Library
  ├─ Header: Audiobooks [headphones shortcut]
  ├─ [All Books | Series] segmented control, ONLY if ≥1 book has a series
  ├─ All Books: total book count, [Title A-Z / Author A-Z] sort, individual rows
  ├─ Series: total series count, A-Z group headings, numbered volume rows,
  │          Standalone Books section at bottom
  └─ Settings: existing Auto-Play Next Chapter and Scan Library Now

Book row body → Player preview (no autoplay)
Book row three dots → Book-specific management menu
Player top-right three dots → Same book-specific management menu
Book menu → Edit Book Details / Mark as Finished / Clear Progress / Remove Audiobook
```

- The Library header shows **Audiobooks** on the left and the headphones shortcut on the right when eligible.
- On a fresh installation, show **All Books**, sorted **Title A-Z**.
- Remember the last selected Library view and All Books sort between app launches.
- **Conditional Series navigation:** show the All Books/Series segmented control if and only if at least one available book has a nonempty *effective series name*. If none, no tab strip appears and the app presents All Books only. When the final series assignment disappears, immediately switch to All Books and hide the selector. When the first series is assigned, reveal the selector but do not automatically switch views.
- No automatic literary-series inference from directory structure, filenames, title, or web metadata. Folder books are *audiobooks made of multiple audio files*, not necessarily book series.
- Returning from Player or editor restores prior Library view, sorting, and scroll position where SDK navigation permits.
- The primary book-row touch target opens Player; tapping three dots must never open Player.
- Existing automatic return-to-active-player behavior is preserved; the new headphones shortcut is an additional manual entry point, not a wholesale navigation rewrite.

## 3. All Books view

**ALL-01, full Library:** show every available book exactly once regardless of series membership, completion, or listening progress. Each multi-part audiobook counts as one book.

**ALL-02, count:** upper-left above list shows `N BOOKS` or `1 BOOK`, counting audiobook records, never audio files.

**ALL-03, typography and content:** every book row has, from most to least prominent:
1. **Book title**, largest/boldest.
2. Author, smaller secondary text, omit line if blank.
3. Optional series name and position, smallest text, e.g. `The Stormlight Archive · #2.5`; if series exists but no position, show series name alone. If no series, omit line entirely; don't print placeholders.
4. Compact right-aligned whole-book percentage **or** finished checkmark, above a separate right-aligned three-dot menu icon. Keep the three-dot position stable when status is absent.
5. Subtle row separators, readable spacing, no cards or cover art. Long titles should wrap sensibly without colliding with right-side controls, with full text accessible on Player/editor.

**ALL-04, sort control:** exactly **Title A-Z** (default, existing) and **Author A-Z**. No reverse directions, no Series sort options. The selector stays above the list on the right, aligned with the total count.

**ALL-05, sorting semantics:** compare complete displayed strings case-insensitively, with leading articles included ("The Bell Jar" sorts under T). Author A-Z sorts **the displayed complete author name**, not inferred surname. For equal author names, sort by Title A-Z; missing authors always last. Exact ties use stable book identity for deterministic results. Use saved edited metadata over imported tags. Never alter audiobook files or progress when sorting.

**ALL-06, sort persistence:** remember the selected All Books sorting choice across navigation and app restarts. Series view has no sorting control.

## 4. Series view

**SER-01, availability:** the segmented control and Series screen appear **only when ≥1 book has a valid, nonempty effective series name**. In the audited baseline there is no dedicated imported series field, so assignments will initially come through the metadata editor; do not infer series from folders. The app never shows a useless empty Series tab.

**SER-02, header:** top-left text `N SERIES` or `1 SERIES`, counting distinct case-insensitive normalized series groups; exclude Standalone Books. No sorting control on Series; series groups are automatically ordered name **A-Z**.

**SER-03, series heading:** series name is visually dominant on the left; far right shows the number of books *present on the device in that series* (`1 book`, `3 books`). Do not infer how many published books exist.

**SER-04, row layout:** each series book row shows numeric series position at the far left (when specified); title immediately right; author below title; whole-book percentage or finished checkmark on far right; separate three-dot action icon below status. Do not repeat the series name under each book because the group heading supplies it. Do not show blank series-number punctuation for unnumbered books.

**SER-05, volume order:** valid numeric positions asc: `1, 2, 2.5, 3`; display integers without trailing `.0`; if position absent, put those books after numbered books, sorted Title A-Z; ties by Title A-Z. Do not renumber volumes or fill missing numbers. Numeric comparisons, never lexical comparisons.

**SER-06, Standalone Books:** place a final `Standalone Books` section at the bottom, right-aligned count (`1 book`, `4 books`), with books Title A-Z. No series position or series line. Hide this section if zero standalone books. These books do not contribute to `N SERIES`.

**SER-07, group normalization:** trim names and compare case-insensitively. `Stormlight` and ` stormlight ` are one group; do not fuzzy-match unrelated titles. For differing capitalization variants, display the effective series name from the group's Title A-Z first book as a deterministic label. Editing one book's series changes **only that book**, not every book in the group.

**SER-08, live updates:** assigning the first series reveals the tab; removing the last series hides it and returns the user to All Books. Counts and group membership update immediately on successful metadata edit, book removal, or confirmed scan changes. An empty series group vanishes.

## 5. Library listening status: not the Player scrubber

**PROG-01, remove bars:** **no progress bars anywhere in All Books or Series rows**, including Standalone Books. Keep the existing Player's chapter-specific interactive scrubber untouched.

**PROG-02, global duration:** library completion percentage must be based on **entire audiobook**. A one-file audiobook uses global position / total file duration. For a folder book: `(duration of all previous parts + position within active part) / sum of all parts' durations`. Embedded chapters do not reset the numerator or denominator. The implementation must investigate the user's observed chapter-only percentage problem rather than just hiding the existing visual bar.

**PROG-03, rendering:** if not finished and duration known, floor the percentage; show nothing when below 1%; show `1%` through maximum `99%` when unfinished. Examples: 0%, 0.8% → blank; 1.7% → `1%`; 24.9% → `24%`; 99.9% unfinished → `99%`. If completed, show a monochrome `✓` check icon **instead of percentage**, even if last saved playback position is 24%. No `0%`, no `100%`, no "Not Started", no "Finished" text in rows. Unknown/invalid duration → no percentage; completion check remains allowed. Accessibility description for check: "Finished".

**PROG-04, automatic completion:** retain the original whole-audiobook near-end behavior (within final ~30 seconds). Never interpret end of chapter 1 as end of the entire audiobook. Preserve existing Player behavior otherwise.

**PROG-05, independent completion:** completion is persisted separately from listening position; manual Finished persists across rescans, restarts, routine player saves, and replay. Playing a Finished book from its end may use the existing restart-from-zero behavior, **but does not clear Finished status**. Only Clear Progress resets completion. An unfinished book may show 99% rather than a check if the near-end threshold has not been met.

**PROG-06, background safety:** global position remains recoverable after continuing playback with Player view closed; a delayed progress save may not reverse manual completion or a confirmed Clear Progress. Updates reflected on Library return without unnecessary media rescans.

## 6. Book management, menus, editing

**MENU-01, availability:** each Library row and Player upper right has a separate vertical three-dot icon acting on *that displayed book*. The same business logic serves both screens. Only **implemented** menu actions should be visible at each incremental release gate; do not show inert future menu entries.

**MENU-02, final contextual menu:** `Edit Book Details` (always); `Mark as Finished` (only if not completed); `Clear Progress` (only if nonzero position or completed); `Remove Audiobook` (always, once safely implemented). Never `Mark as Unfinished`. Other app-wide Settings are separate. Only one menu open; outside tap or Back dismisses; opening it doesn't affect audio. Library menu icon must not propagate tap to book row.

**META-01, editor:** accessible from a book's menu, with editable Book Title, Author, Series Name, Series Position. Explicit **Save** and **Cancel**, no implicit autosave. Cancel preserves old metadata. Back with unsaved changes offers `Keep Editing` and `Discard Changes`. Save failure shows an error rather than false success.

**META-02, validation:** Title required, non-whitespace; Author optional; Series Name optional; Series Position optional but only valid with Series Name. Trim leading/trailing whitespace. Up to 255 characters each for title, author, series name, with visible validation errors; do not silently truncate. Position is a nonnegative decimal with a period separator (valid 0, 1, 2.5; invalid -1, `two`, `2,5`, `1..5`); omit trailing zeroes in display. Clearing Series Name clears Position.

**META-03, imported vs override:** for each field use explicit user override if present (including intentionally emptied optional fields); otherwise imported audio metadata; otherwise original filename/folder fallback. Never write user edits to audio tags. Metadata overrides must survive app restarts/rescans, update both Library and Player, and drive sort/grouping. `Restore Imported Details` fills an *unsaved editor draft* from original metadata/fallback, requiring Save to commit. Editing active book must not interrupt playback.

**META-04, identity:** book title/author are **not unique IDs**; duplicate names stay separate. Retain existing path-hash book identification for this release. Renaming/moving file/folder may create new identity and disconnect prior progress/overrides. This is a known limitation to document, **not** a reason to introduce speculative automatic matching in this release.

**FIN-01, mark:** Mark as Finished applies immediately without confirmation, persists independently from position, shows checkmark, removes Mark action, makes Clear Progress available. Does not pause, seek, alter tags, or reset; the book may continue playing if already playing. No false success if save fails.

**CLEAR-01, confirmation:** `Clear listening progress?` message: `This will reset your listening position for "[Title]" to the beginning and remove its Finished status. Your audiobook files and edited book details will be kept.` Buttons `Cancel` / `Clear Progress`.

**CLEAR-02, semantics:** on confirmed success, global position=0, completed=false, library status blank, metadata/files retained, and future bookmarks retained. Preserve Last Played ordering. When selected book actively playing, pause it and reset to beginning, leaving paused; other book's audio must be untouched. Cancel changes nothing. A delayed progress save must not restore old position. If save fails, report it and recover consistent state.

## 7. Headphones: Return to Last Played

**LAST-01, selection:** Library header shortcut picks (a) currently playing book in *this app*; otherwise (b) most recently successfully played **currently available** book; otherwise hidden. If currently paused and the live session is available, it picks that book ahead of an older history reference. Do not use unrelated system audio sessions.

**LAST-02, durable history:** update ordering upon **actual successful start/resume of playback**, not merely opening a Player preview, editing metadata, changing sort, or clearing progress. Persist enough history to fall back if most recent book is physically deleted. Replay pushes book to most recent. Store locally across app/process/device restarts. Do not blindly trust existing `lastPlayedAtMilliseconds` until verified: initial repository's current `saveLocal()` does not update it.

**LAST-03, action:** open Player without autoplay. Preserve live play/pause state when attached to existing queue. If no live queue remains, load book using saved position *paused*. Reconnect to valid SDK detached player without duplicating/replacing already playing queue. Reaching finished end and reopening must not automatically restart.

**LAST-04, missing/deleted:** skip unavailable books when finding shortcut target. Explicitly successfully removed books leave history. Temporary scan/permission errors must not permanently wipe history. Preserve existing automatic navigation behavior that brings users back to actively playing audio, avoiding loops and duplicate Player screens.

## 8. Safe audiobook removal

**REM-01, purpose:** real deletion of audio files to reclaim device space, not merely hiding/removing rows. The supported deletion path for each storage root requires a feasibility check against current Light SDK / Android restrictions *before* building destructive UI.

**REM-02, dialog:** `Remove audiobook?` and `Remove "[Title]" and its audio files from this phone? Your saved listening progress, edited details, and bookmarks for this audiobook will also be deleted. This cannot be undone.` Buttons `Cancel` / `Remove`. Cancel changes nothing.

**REM-03, exact ownership:** identify precise audio part files for selected book; validate canonical paths within approved storage roots. Single file → that file. Folder book → only its recognized audio files. Never delete unrelated files, unrecognized sibling files, shared parent directories, or files outside approved roots. Do not delete directory recursively as a shortcut.

**REM-04, authorization:** reading permission is not assumed to grant deletion permission. Obtain SDK-compatible authorization where required. If unsupported, report a true limitation and preserve book; do not fake removal by hiding or renaming. Confirm only after deletion has actually succeeded.

**REM-05, active playback:** only after user confirmation and necessary OS authorization, safely stop the *selected* book if it is playing, then perform deletion. Removing a different book does not interrupt current audio. From Player, navigate to Library on successful removal. If deletion fails after stopping, state must remain recoverable, with accurate error.

**REM-06, successful cleanup:** after all relevant audio parts are confirmed deleted, remove book from visible list, progress, completion, overrides, last-played history, and bookmark data if later supported. Update book/series/standalone counts, remove empty group, reconcile scan cache. Do not reappear on scan.

**REM-07, partial failure:** never claim success if only some audio files deleted. Report incomplete removal; reconcile surviving parts; preserve recoverable state; do not erase all app metadata while some files remain. Test USB/shared and Tool Manager/private roots separately on actual Light Phone III.

## 9. Scanning, storage, errors, upgrades

**SCAN-01:** retain manual `Scan Library Now` in Settings and existing initial/Tool Manager-triggered scans. Scanning only reflects local storage; it does not transfer/upload audio. Switching view/sort must not trigger expensive rescans.

**SCAN-02:** preserve user overrides, manual Finished, playback history, valid progress, future bookmarks for unchanged book identity. Scan errors, empty folders, absent folder, missing permission, and genuine removal are different outcomes. Never erase good data merely due to transient read/permission failures.

**DATA-01:** logical data includes book identity, source root/part references, imported tags, user overrides (including explicitly cleared optional values), optional series position, total duration, global position, completion, speed, last played ordering, and file size. Implementation must not prescribe a needless new database if the current local store can safely support this.

**DATA-02:** storage updates must be atomic/consistent, errors observable, existing v0.9.2 progress migrated without loss, concurrent operations serialized or protected. A stale async playback save must not undo Clear Progress or overwrite a newer manual Finished state. Do not overwrite corrupt/unreadable persisted files with empty defaults.

**DATA-03:** local-only privacy, no server account, no analytics/telemetry. Use Light SDK-supported APIs; no extra permissions/dependencies without explicit necessity and review.

## 10. Empty states, reliability and accessible UI

- Empty library: `0 BOOKS` with the existing practical local-transfer guidance; no in-app Add button.
- Only standalone books: no segmented control, only All Books; Series view unavailable.
- At least one series and no standalones: Series tab and groups visible, omit Standalone Books heading.
- Unknown/blank author: omit the line, author sorting puts missing authors last.
- Pending scan: avoid falsely showing a permanent empty library before initial scan concludes.
- Permission denied/read failure: show actionable error; don't clear saved progress/history.
- Book file removed externally: successful scan may remove it from visible library; don't erase persistent history on merely temporary inaccessible scan.
- Long titles: text readable with controlled wrapping/truncation, no overlap of right-side controls, full title in editor/Player.
- Icon-only controls must have accessibility labels (`Return to last played audiobook`, `More options for [Title]`, `Finished`, `Back`).
- No cover art, colored badges, shadows, speculative screens, or nonfunctional menu placeholders.

## 11. Verification and source-of-truth rules

The companion implementation playbook defines ordered feature gates, detailed QA scenarios, and exact stop/go requirements. A code compile is not adequate verification. Each feature must be independently QA'd and approved before beginning the next.

For any discrepancy, use this precedence:
1. Latest *explicit* product-owner decision in the development conversation.
2. This PRD v1.3.
3. `LIGHT_AUDIOBOOKS_IMPLEMENTATION_PLAYBOOK_v1.0.md` for ordering and QA (not contradictory product behavior).
4. Current checked-out repository's actual source.
5. Official current Light SDK documentation.
6. Original README/ROADMAP/PLAN.md as historical context only; their upstream Won't-do list is not binding on this fork.

Do not silently overwrite product decisions based on what seems customary in other Android apps. Report any genuine infeasibility with code/SDK evidence and alternatives; do not implement a watered-down workaround without approval.
