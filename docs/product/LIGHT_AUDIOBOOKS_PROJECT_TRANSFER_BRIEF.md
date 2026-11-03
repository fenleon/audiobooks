# Light Audiobooks: Project transition brief

**As of November 3, 2026.** This file is a continuity note, not a replacement for the PRD or Phase Playbook.

## Product and workflow decisions
- Repo: https://github.com/sairansorosie/audiobooks, `main` at last audited SHA `d37a172a93aca4366e22678ff000ca6dd41016dc`, v0.9.2.
- Authoritative requirements: `LIGHT_AUDIOBOOKS_PRD_v1.3.md`. Implementation/acceptance sequencing: `LIGHT_AUDIOBOOKS_IMPLEMENTATION_PLAYBOOK_v1.0.md`. Repo coding guidance: `AGENTS.md`.
- Incremental workflow: read-only phase plan, owner approval, focused code edit on feature branch, automated build/tests, owner QA, explicit acceptance, then merge and next phase.
- All Books sort options: **Title A-Z** and **Author A-Z** only. No Series sort selector. Show the All Books / Series switch only if at least one book has a series assignment.
- Phase 01 focus: remove Library progress bars; hide <1%/0%; show whole-audiobook 1–99% floored percentage or completed checkmark; preserve Player's chapter-specific progress scrubber.
- Other planned phases: book rows; persistent metadata; editor; conditional Series view; sorting; Mark Finished; Clear Progress; Player actions; Last Played; real file deletion; full regression QA.

## Verified local working environment
- Mac folders: `~/Documents/Developer/audiobooks` (actual GitHub fork) and `~/Documents/Developer/light-sdk-official` (official Light SDK checkout and working emulator build workspace); `~/Documents/Developer/light-sdk` symlink points to `light-sdk-official`; `light-sdk-fenleon` also present.
- From `audiobooks`, `git log -1` showed `d37a172`, and `origin` pointed to the fork. `git status --short` showed exactly one uncommitted modification to `tool/lighttool.toml`: `serverPackage` changed from `com.lightos` to `com.thelightphone.sdk.emulator` to support emulator testing. **Do not include this modification in a documentation commit.**
- The standalone `audiobooks` build failed against official SDK dependency allowlist (`com.thelightphone:sdk-client`), even with the alternate server package flag. This does not establish an application-code defect.
- The product owner reported successful SDK-workspace build **and tests** from `light-sdk-official`:
  `./gradlew :tool:assembleDebug -DlightSdk.allowAltServerPackage=true`
  `./gradlew :tool:testDebugUnitTest -DlightSdk.allowAltServerPackage=true`
- Official builder documentation explains a similar workflow: extract a tool's `tool/` source and stage it inside a pinned Light SDK workspace for compilation. Local manual copying was a temporary setup; a **repeatable staging/build script** should be planned before the first app feature PR. Do not modify official SDK policy or silently overwrite the working workspace.

## GitHub documentation status
- GitHub `main` had **no `AGENTS.md` or PRD/Playbook committed** at the time of the audit.
- Attempt to create `docs/add-agent-instructions` via ChatGPT's GitHub connector failed with HTTP 403, so **nothing was pushed or merged**.
- User has a prepared documentation ZIP containing `AGENTS.md`, `docs/product/LIGHT_AUDIOBOOKS_PRD_v1.3.md`, `docs/product/LIGHT_AUDIOBOOKS_IMPLEMENTATION_PLAYBOOK_v1.0.md`.
- Use the local Audiobooks checkout to create a dedicated docs branch; unpack the files, stage **only** `AGENTS.md` and `docs/product/` (not `tool/lighttool.toml`), verify the diff, commit and push, then open a PR. A manual GitHub PR approval is required before merging.

## Next steps
1. Move this conversation to the new ChatGPT **Audiobooks** Project with the chat menu's **Move to project** action if eligible; otherwise start a new Project chat and provide this transfer brief.
2. Upload the PRD and Playbook to **Project Sources**; add review/approval instructions to Project settings.
3. From the local `audiobooks` checkout, commit `AGENTS.md` + two docs via a documentation-only branch and PR. Keep emulator TOML change uncommitted.
4. Mark Phase 00 as pending **source provenance and repeatable build process** verification, although SDK-workspace baseline build and test succeeded. No app features implemented yet.
5. Work through Phase 01 after explicit product-owner approval.
