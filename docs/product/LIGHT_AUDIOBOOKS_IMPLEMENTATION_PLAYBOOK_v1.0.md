# Light Audiobooks: Incremental Implementation and QA Playbook

**Version:** 1.0; paired with `LIGHT_AUDIOBOOKS_PRD_v1.3.md`.  
**Method:** one narrowly scoped feature → code inspection and plan → approval → implementation → automated verification → product-owner QA → fix → explicit approval/merge → next feature.

## A. Environment and roles

**ChatGPT Project = product owner workspace:** authoritative PRD and playbook, decision log, QA evidence, and review of plans. A ChatGPT Project with GitHub connected can generally **read/search** source, not automatically edit/commit code.

**Codex = coding workspace:** make changes in a checked-out repository (local Codex desktop/CLI, or configured Codex cloud environment), run tests, present diff/PR. Codex local is convenient because the user already has a working Android Studio/emulator setup and the repository requires a sibling `../light-sdk` checkout. Do not assume the ChatGPT Project and Codex share local files or configuration without explicitly connecting them.

**Android Studio + emulator + physical Light Phone III = QA:** the product owner installs and checks the APK; reports pass/fail with screenshots, logs, or reproduction steps. Emulator cannot prove real LightOS permissions/background audio.

**GitHub = system of record:** `https://github.com/sairansorosie/audiobooks`, default `main`. Create one branch/PR per approved feature. Keep working tree clean, commit accepted changes, do not merge without product-owner approval.

### Before work starts

- Ensure the local Android Studio project is the **fork**, not only the original upstream clone. In the repository terminal, run `git remote -v`, `git status --short`, `git log -1 --oneline`. Inspect origin URL and pending local modifications. Do not switch/clean/reset while there are uncommitted changes.
- Repository README requires JDK 17/21, Android SDK API 36, and sibling `../light-sdk` checkout. Gradle module is `:tool`, **not** `:app`. However, with the official SDK selected by the local `../light-sdk` symlink, **the standalone `audiobooks/` Gradle build fails** the SDK dependency allowlist (`com.thelightphone:sdk-client`). Do not claim the standalone command is the currently validated build workflow.
- **Validated local build workspace (2026-11-03):** `~/Documents/Developer/light-sdk-official` with the Audiobooks `tool/` module staged there. The product owner successfully ran `./gradlew :tool:assembleDebug -DlightSdk.allowAltServerPackage=true` and `./gradlew :tool:testDebugUnitTest -DlightSdk.allowAltServerPackage=true` from that SDK workspace. These are baseline outcomes as reported by the product owner, not proof that subsequent edits in `audiobooks/` are already staged or tested.
- Local Audiobooks `tool/lighttool.toml` currently has an uncommitted emulator override (`serverPackage = "com.thelightphone.sdk.emulator"`); the repository version retains `com.lightos` for a real phone/Light's distribution builder. Preserve this separation. **Never commit an emulator-only serverPackage override as a production change.**
- Light's official builder (`https://github.com/lightphone/light-sdk/blob/main/builder/README.md`) stages extracted `tool/` files inside a pinned SDK workspace. Design a repeatable non-destructive local staging/build script *as a separate approved infrastructure task* so developers do not have to edit two app copies manually. Do not silently overwrite the current working SDK workspace. Never disable the SDK's dependency allowlist to get a green build.
- Capture existing behavior with the known working emulator/audio fixture; revalidate actual staged app behavior and record its source commit or diff.
- At Gate 00, investigate removal API feasibility and any package/signing constraints without writing destructive code.
- Do not copy development signing keys or private credentials into the repository or ChatGPT Project.

## B. Master acceptance rule

A phase is **Accepted** only when (1) code implements that phase's precise requirements, (2) applicable automated tests and build pass, (3) the product owner personally verifies expected behavior, (4) existing playback/saved data are not regressed, (5) screenshots/logs and pass/fail evidence are recorded, and (6) the product owner explicitly says **“Accept phase N”**. Agent must not start the next phase without that approval.

For a change not observable through UI (e.g., metadata store): accept from focused automated tests, persisted on-device evidence, and a brief documented demonstration without adding temporary production UI.

## C. Ordered implementation gates

### Phase 00: Repository baseline and safety audit
**Source refs:** PRD §§1, 3, 9, 11; baseline and SDK constraints.  
**Scope:** No application code edits. Verify `origin`, branch, uncommitted state; JDK/Android SDK/sibling Light SDK; discover Gradle build/test targets; build v0.9.2; launch emulator; document Library, Player, chapters, Settings, scan, pause/resume; map source files to planned phases; document deletion permissions feasibility path and signing/package identity risk.

**Acceptance checklist:**
- [ ] Correct fork's `main` identified; no accidental edits to upstream repository.
- [ ] Existing local changes preserved and reported, not reset.
- [ ] SDK sibling path verified or flagged.
- [x] SDK workspace `:tool:assembleDebug` and `:tool:testDebugUnitTest` reportedly succeeded with `-DlightSdk.allowAltServerPackage=true` on 2026-11-03. Record the staged `tool/` provenance before treating this as verification of the current GitHub fork.
- [ ] Repeatable tool-source staging/build process defined and approved; standalone dependency-validator failure documented, not bypassed.
- [ ] Existing relevant tests discovered and results logged (not assumed).
- [ ] App launches, scans sample book, plays, pauses, shows chapter navigation in emulator.
- [ ] Current source behavior and known limitations documented (not replaced by old README guesses).
- [ ] Safe-deletion feasibility research initiated separately for shared and private roots; any unresolved device permissions explicitly marked.
- [ ] No source modifications, pushes, or merges during this gate.

**Owner QA:** confirm existing emulator test book loads and audio plays; save the baseline evidence. **Exit:** approval of baseline report.

### Phase 01: Whole-book status, no Library bars
**Source refs:** PROG-01 to PROG-04, PROG-06; ALL-03 right-side status.  
**Scope:** Remove Library's `LightProgressBar`, hide all `<1%` indicators, show whole-book floor-percent `1%..99%` when unfinished and checkmark when completed; preserve Player `LightTouchableProgressBar`. Investigate actual chapter-vs-book discrepancy. No new row redesign or menus yet.

**Acceptance checklist:**
- [ ] No Library progress bars in any existing Library row.
- [ ] 0% and 0.8% show blank; 1.7% shows `1%`; 24.9% shows `24%`; unfinished 99.9% shows `99%`.
- [ ] Completed book shows checkmark, never `100%` or `Finished` text in row; check has accessible description.
- [ ] Multi-file book at end of early chapter does **not** appear near end of whole book.
- [ ] Embedded-chapter files use global total duration for Library percentage.
- [ ] Unknown duration displays no invented percentage.
- [ ] Player's chapter scrubber, play/pause, seeks, and chapters unchanged.
- [ ] No extra dependency/architecture change.

**Owner QA:** prepare one single-file book and a folder book of multiple chapters; check early chapter nearly finished, check 0% and completed book. **Exit:** accept exact Library statuses.

### Phase 02: Book rows and total count
**Source refs:** ALL-01 to ALL-03, ALL-05 layout details; LIB header/navigation §2 limited to current page.  
**Scope:** Title largest, author secondary, optional series tertiary once available, right status positioning, proper spacing/separators, `N BOOKS` count. Do **not** add a visible three-dot button that has no implemented actions; add the icon only when editor menu is functional in Phase 04. Do not introduce tab selector early.

**Acceptance checklist:**
- [ ] Largest/boldest text is title, author smaller, optional series smallest.
- [ ] Missing author/series adds no empty placeholder row.
- [ ] Status area right-aligned, doesn't cover book text; future menu area reserved appropriately.
- [ ] Title/author of varying lengths display without overlap; full text accessible elsewhere.
- [ ] Count represents books, not audio parts; singular `1 BOOK`, plural `N BOOKS`.
- [ ] Selecting content opens correct book without auto-starting or replacing other live audio.
- [ ] No decorative cover art, cards, shadows, or inert icons.

**Owner QA:** long title, missing author, one multi-file book counted once, book-row tap behavior. **Exit:** approve row layout.

### Phase 03: Persistent metadata and migration foundation
**Source refs:** META-03, META-04, DATA-01 to DATA-03, SCAN-02.  
**Scope:** Implement per-book imported/effective/override metadata for title/author/series/position, including explicit empty optional overrides. Keep original tags untouched. Store safely, migrate v0.9.2 progress, avoid path-based identity changes. No editor, tabs, or unrelated UI in this phase.

**Acceptance checklist:**
- [ ] Metadata override takes priority over imported tags/fallback consistently.
- [ ] Deliberately blank optional value differs from "no override".
- [ ] Saved overrides survive restart and rescan for unchanged book identity.
- [ ] Existing v0.9.2 progress and playback speed remain intact after migration.
- [ ] No rewriting original MP3/M4B tags or renaming files.
- [ ] Duplicate titles remain different records by identity.
- [ ] Bad/corrupt read is not silently overwritten with empty good data.
- [ ] Focused automated persistence/migration tests exist, pass, and are reported.
- [ ] No temporary production UI or inert menu controls added to demonstrate storage.

**Owner QA:** inspect test results and evidence of persisted values after process restart/rescan. **Exit:** approve data foundation.

### Phase 04: Three dots and Edit Book Details
**Source refs:** MENU-01/02; META-01 to META-04.  
**Scope:** Functional independent three-dot menu in All Books, initially containing only **Edit Book Details**; editor fields Title, Author, Series Name, Series Position; Save/Cancel, Restore Imported Details, unsaved changes warning, validation. Book-row selection remains separate.

**Acceptance checklist:**
- [ ] Tap row → Player; tap three dots → menu only.
- [ ] Only Edit Book Details appears until later actions are ready.
- [ ] Can edit required title and optional author/series/position; valid fractional 2.5.
- [ ] Reject whitespace-only title, negative/invalid position, and position with no series.
- [ ] Trim whitespace, enforce 255-character text limits with visible errors.
- [ ] Clearing series clears position; blank author remains intentionally blank on rescan.
- [ ] Save persists and changes effective labels; Cancel discards; Back with edits offers Keep Editing / Discard Changes.
- [ ] Restore Imported Details populates draft but requires Save.
- [ ] Editing while another/current book plays does not interrupt audio.
- [ ] Menu dismisses on outside tap or Back and doesn't open book accidentally.

**Owner QA:** edit a title, author, and series; cancel another edit; try invalid inputs; restart/rescan. **Exit:** approve functional editor.

### Phase 05: Conditional Series view
**Source refs:** §2 segmented behavior, SER-01 to SER-08; ALL-03 tertiary line.  
**Scope:** Show tabs only when a real user-defined series exists. Series headings and counts, volume position ordering, standalone section and count, A-Z series order; no Series sort dropdown.

**Acceptance checklist:**
- [ ] Zero series → no All Books/Series segmented selector; just All Books.
- [ ] Assign first book to series → selector appears, view remains All Books.
- [ ] Series view header `N SERIES` excludes Standalone Books.
- [ ] Series title prominent with far-right count of owned books, correct singular/plural.
- [ ] Rows show number on left; title and author next; progress/checkmark and menu on right.
- [ ] Series name not repeated under each grouped book.
- [ ] Within series numeric order 1, 2, 2.5, 3; missing number after numbered; tie Title A-Z.
- [ ] Series group names auto A-Z; no series sorting selector.
- [ ] Standalone Books section last, Title A-Z, count right; hidden if empty.
- [ ] Name comparisons case-insensitive and trimmed, no fuzzy merging.
- [ ] Remove final assignment → automatically return to All Books and hide tabs.
- [ ] View change preserves audio and metadata; prior scroll state restored if supported.

**Owner QA:** zero-series, one-series, two-series, 2.5 volume, blank volume, only series/no standalone, remove final series. **Exit:** approve Series view.

### Phase 06: Simplified sorting
**Source refs:** ALL-04 to ALL-06.  
**Scope:** All Books dropdown with **only** Title A-Z and Author A-Z; no sorting control in Series. Persist All Books selection.

**Acceptance checklist:**
- [ ] Exactly two All Books choices; Title A-Z default.
- [ ] Title includes articles, so "The Bell Jar" sorts under T.
- [ ] Author uses full displayed name, so "Brandon Sanderson" sorts under B.
- [ ] Books with same author sort by Title A-Z; missing authors last.
- [ ] Case-insensitive comparison; stable identity final tie-breaker.
- [ ] Edits to title/author immediately affect order.
- [ ] Selected sort persists through tab changes and restarts.
- [ ] Series group order stays A-Z and numerical volume order unchanged.
- [ ] No Author Z-A, Title Z-A, or Series sort options.
- [ ] Sorting does not change audio, playback position, or completion.

**Owner QA:** sample titles beginning The, duplicate author, blank author, edited author, reopen app. **Exit:** approve sorting.

### Phase 07: Manual Mark as Finished
**Source refs:** PROG-04 to PROG-06, FIN-01, MENU-02.  
**Scope:** Completion independent from playback position and routine saves; add Mark as Finished to contextual menu only when not completed. Preserve current automatic near-end completion. Do not build Clear Progress ahead of next phase.

**Acceptance checklist:**
- [ ] Mark as Finished available for every unfinished book, including never-started.
- [ ] Clicking immediately sets completed and displays a checkmark, no confirmation.
- [ ] Original global position preserved, even at 24%.
- [ ] Does not pause, seek to end, or change tags.
- [ ] Survives player progress-save cycles, rescan, process restart, and future replay.
- [ ] Finished book no longer offers Mark as Finished.
- [ ] Replaying from saved end starts normally but retains completed checkmark.
- [ ] Completion save failure does not claim success.
- [ ] Regression automated test verifies late progress save cannot unset manual Finished.

**Owner QA:** mark a 24% book finished, restart and play/stop it, verify check persists and position retained. **Exit:** approve Finished.

### Phase 08: Clear Progress
**Source refs:** CLEAR-01/02; PROG-05/06; MENU-02.  
**Scope:** Contextual Clear Progress when saved position nonzero or completed; confirmation, global reset, safe concurrent persistence; preserve metadata, files, future bookmarks, and Last Played order.

**Acceptance checklist:**
- [ ] Clear Progress absent for never-started, unfinished book.
- [ ] Confirmation identifies correct book, effects, Cancel/Clear Progress.
- [ ] Cancel does not modify anything.
- [ ] Confirm resets global position to zero and completed false; Library indicator blank.
- [ ] Files, edited details, and playback-speed preference survive.
- [ ] If selected book is playing, pause/reset and leave paused; unrelated audio not touched.
- [ ] Stale async progress save cannot restore prior position/state.
- [ ] Failure to persist reports error and avoids false success.
- [ ] Last Played reference/order remains unchanged.

**Owner QA:** reset 24% book, reset completed book, reset active book, cancel, restart. **Exit:** approve Clear Progress.

### Phase 09: Player three-dot menu
**Source refs:** MENU-01/02; PRD §2 navigation and §1 playback preservation.  
**Scope:** Add Player upper-right book-specific three-dot control using same action logic as Library. Only implemented items shown (Edit, Mark, Clear); Remove comes in Phase 11. Existing transport controls untouched.

**Acceptance checklist:**
- [ ] Three-dot control shown upper right of Player; Back remains available.
- [ ] Menu refers to **displayed book**, not another book currently playing in background.
- [ ] Edit, Mark, Clear produce identical results from Player and Library.
- [ ] Menu opening/closing has no playback effect.
- [ ] Existing bottom Settings/Speed/Chapters, skip ±15, Player scrubber unchanged.
- [ ] Return from metadata editor restores Player with consistent updated title/author.
- [ ] Long title does not collide with header actions.

**Owner QA:** open another book in preview while different book plays, edit previewed title through Player menu, ensure unrelated audio unchanged. **Exit:** approve Player menu.

### Phase 10: Persistent Return to Last Played
**Source refs:** LAST-01 to LAST-04; PRD §2 header and existing playback preservation.  
**Scope:** Headphones shortcut in top-right Library; durable real playback history; active/paused current player priority; fallback to most recently played available book; no autoplay; preserve original auto-return navigation.

**Acceptance checklist:**
- [ ] With no books ever played, headphones shortcut hidden.
- [ ] Starting/resuming Book A records A; opening Book B in preview does not replace history.
- [ ] Playing Book B updates priority to B.
- [ ] While active Book B plays, icon reconnects without restarting/double queue.
- [ ] Paused live Book B reopens paused at same position.
- [ ] After app/process/device restart, most recent book opens paused at saved position.
- [ ] Completed book opens paused; no surprise restart.
- [ ] After B is removed or genuinely unavailable, fallback A if available.
- [ ] Clearing A's progress does not remove A from history.
- [ ] Temporary scan/permission failure doesn't permanently wipe history.
- [ ] Original automatic return to active player still works; no navigation loops.
- [ ] Background listening position recovered accurately.

**Owner QA:** Play A, open B without playing, test icon, play B, restart application, pause/resume, verify no autoplay. **Exit:** approve Last Played.

### Phase 11: Safe Remove Audiobook
**Source refs:** REM-01 to REM-07; MENU-02; SCAN-01/02; LAST-04.  
**Prerequisite:** Phase 00 feasibility findings for real deletion in both storage roots.  
**Scope:** Add Remove action in Library and Player; confirmation; validated file ownership; SDK-compliant authorization; exact deletion; stop selected active queue where necessary; reconcile storage/library/data/history. No fake hide functionality.

**Acceptance checklist:**
- [ ] Confirmation with title, permanent file/data consequences, Cancel and Remove.
- [ ] Cancel/denied authorization performs no deletion or state change.
- [ ] App identifies precise owned parts using canonical approved paths.
- [ ] Single-file deletion deletes only selected file.
- [ ] Multi-file deletion deletes only recognized parts, no unrelated sibling/parent files.
- [ ] Permission and supported SDK API verified on actual Light Phone III.
- [ ] Successful deletion physically frees selected audio files; removes book and its per-book app data.
- [ ] Counts, group, and last-played fallback update after confirmed success.
- [ ] Removing active book safely stops it; other playing books not disturbed.
- [ ] Partial deletion or failure reported without false success or indiscriminate data wipe.
- [ ] Rescan never resurrects successfully removed files due to stale cache.
- [ ] Both shared USB and private Tool Manager storage separately tested.

**Owner QA:** use disposable test books (single and multi-part) plus unrelated files; verify actual file deletion via device storage, error handling, and ability to play other books. **Exit:** approve only when real file deletion verified.

### Phase 12: End-to-end regression and release readiness
**Source refs:** every PRD section.  
**Scope:** Re-run full critical flows on emulator and physical Light Phone III, ensure clean migration from v0.9.2, verify SDK restrictions/privacy, fix only demonstrated regressions, update README/build docs/license notices, and review release identity/signing separately.

**Acceptance checklist:**
- [ ] Clean `:tool` build and relevant tests pass (commands + output recorded).
- [ ] Existing USB and Tool Manager imports and Scan Library Now work.
- [ ] All existing formats tested to reasonable extent; verify core multi-file and embedded chapter behavior.
- [ ] Play/pause, 15s seeks, chapter picker, speed, auto-play-next, auto-rewind, background playback still function.
- [ ] No Library progress bars/0%; whole-book percent and checkmarks accurate.
- [ ] Conditional Series tab, counts, volumes, standalones, two sorts behave correctly.
- [ ] Metadata edits, Finished, Clear, Last Played survive restarts/rescans and do not race.
- [ ] Safe removal works only on authorized supported roots and never claims false success.
- [ ] Accessibility labels and long titles verified.
- [ ] No analytics, network tracking, account system, cover art, or unrelated new features.
- [ ] Old progress migrates; packaging and signing explicitly reviewed; notices/license retained.
- [ ] Known limitations documented; no merge/tag/publication without approval.

**Owner QA:** formal end-to-end signoff. **Exit:** release candidate eligible for separate publication approval, not automatic publication.

## D. Per-feature chat/agent handoff template

**Before code:** `We are on Phase NN only. Read PRD and playbook. Inspect current repository read-only. Report existing behavior, exact files to change, minimum approach, automated tests to add/run, risks, and manual QA checklist. Do not edit, branch, commit, or push until I approve.`

**After plan approval:** `I approve the Phase NN implementation plan. Implement Phase NN only on a dedicated feature branch. Preserve all unrelated functionality. Run build and relevant tests, show changed-file list and diff summary, and stop for my QA. Do not merge, publish, or start Phase NN+1.`

**After user QA defects:** `Phase NN is not accepted. Reproduce the following defect: ... Fix only within Phase NN, add regression coverage, run tests, and stop for re-QA.`

**After QA passes:** `I accept Phase NN. Summarize final evidence, changed files, and known limitations; propose the merge or PR action for my separate approval. Do not begin Phase NN+1 until I explicitly ask.`

## E. QA report template

```text
Phase: NN - [name]
Branch / commit / PR:
APK build result and command:
Automated tests and results:
Device: emulator / physical Light Phone III / both
Test input books/files:
Acceptance tests passed:
Acceptance tests failed:
Reproduction details / screenshots or logs:
Regressions noticed:
Decision: Accepted / Changes required / Blocked
Approval to merge: Yes / No
Next phase explicitly authorized: Yes / No
```

## F. Red flags requiring immediate stop

- Implementer adds irrelevant screens or features.
- Inert three-dot entries presented before those actions exist.
- Changing package ID or signing without approval.
- Claiming a test passed without running it.
- Claiming actual file removal without checking storage.
- Using the old `:app` build target or old companion architecture.
- Deleting shared folders recursively.
- Rewriting the existing detached audio service without demonstrated need.
- Assuming cached path-hash IDs survive source file rename/move.
- Dropping existing progress during migration.
- Starting the next phase before product-owner acceptance.
