# Bard Repository Guide

## Vision

Bard is a minimalist audiobook player designed for the Light Phone.

Its purpose is simple:

**Help people listen to stories.**

Bard is not trying to recreate Libby.
It is not trying to become a podcast app, ebook reader, media player, or library management application.

Everything in this repository should support a calm, distraction-free listening experience.

---

# Product Philosophy

Every feature should make Bard simpler.

Whenever multiple solutions are technically correct, prefer the one that:

- requires fewer taps
- introduces less UI
- uses fewer dependencies
- has fewer moving parts
- is easier to maintain
- feels more like the Light Phone

Never optimize for feature count.

Always optimize for calmness.

When in doubt, leave it out.

---

# User Experience

The application consists of three primary experiences:

- Books
- Player
- Settings

All books appear together in one unified library.

The user should not need to think about where a book came from.

The player should be identical for every book.

---

# Sources

Bard is a local-only audiobook player.

All audiobooks come from the shared `Audiobooks` folder on device storage. A single audio file directly inside `Audiobooks/` is a standalone book. Every folder inside `Audiobooks/` is one book whose audio files play continuously in filename order.

There is no Libby, RSS, streaming, or cloud source, and the architecture should never grow one implicitly.

---

# Light Design Language

Bard should feel like a native Light Phone application.

It should never feel like a standard Android application wearing a Light Phone skin.

When implementing UI:

## Typography

- Prefer the existing Light SDK typography primitives.
- Use `LightText` with the appropriate `LightTextVariant`.
- Do not create custom typography scales.
- Avoid decorative typography.
- Create hierarchy through existing text variants rather than custom styles.

## Layout

- Use the Light grid system (`gridUnitsAsDp`) for spacing.
- Prefer generous whitespace.
- Maintain a consistent vertical rhythm.
- Keep layouts simple and easy to scan.
- Prefer scrolling over dense information.

## Components

- Prefer existing Light SDK components whenever possible.
- Extend existing Bard or Light components before creating new ones.
- Use `lightClickable` where appropriate.
- Avoid unnecessary custom controls.

## Color

- Use the existing Light theme.
- Do not hardcode colors.
- Do not introduce accent colors.
- Avoid decorative borders, gradients, and shadows.

## Motion

- Motion should communicate state only.
- Avoid unnecessary animations.
- Fast interactions are preferred over elaborate transitions.

## Information Hierarchy

Display only information that helps the user choose a book or continue listening.

Avoid metadata that does not directly improve the listening experience.

Hierarchy should come from typography and whitespace rather than decoration.

## Whitespace

Whitespace is a design element.

Do not fill empty space unnecessarily.

A screen with fewer elements is often the better screen.

## Consistency

Identical actions should always look identical.

Maintain consistent spacing, typography, navigation, and interaction patterns throughout the application.

## Restraint

Every feature increases complexity.

Before implementing any feature, ask:

- Does this solve a real listening problem?
- Can this require fewer taps?
- Can it use less UI?
- Is it consistent with the Light Phone philosophy?

If the answer is uncertain, prefer not to implement the feature.

---

# Current Architecture

This repository is a single-module Kotlin Android application using Jetpack Compose.

Current major components:

- `BardApplication` — initializes progress storage, the local book repository, and playback.
- `LocalBookRepository` — scans `Audiobooks/` (any depth) into single-file and folder books.
- `LocalPlaybackController` — MediaPlayer playback, including continuous multi-part playback.
- `PlaybackForegroundService` — keeps playback alive in the background.
- `AudiobookProgressStore` — persistent listening position, speed, and ordering metadata.
- `PlayerDebugScreen` — the Compose UI (library, player, settings).
- `ui/` — reusable Light-style Compose components.

The UI package already contains reusable Light-style Compose components.

---

# Architectural Direction

The application should evolve toward simple domain models.

Introduce abstractions only when they support an implemented feature.

Avoid speculative architecture.

Preferred future concepts include:

- Audiobook
- AudiobookId
- AudiobookSource
- PlaybackController
- LibraryRepository

The UI should consume these abstractions rather than interacting directly with filesystem or MediaStore code.

---

# Naming

User-facing text should refer to the application as **Bard**.

Internal package names and legacy identifiers may remain until a dedicated migration.

Avoid unnecessary renaming of working code.

---

# Dependencies

Prefer the existing Android SDK and Light SDK.

Do not introduce third-party libraries unless there is a clear technical justification.

Smaller dependency graphs are preferred.

---

# Privacy

Never log or expose:

- passwords
- cookies
- authentication tokens
- signed URLs
- authorization headers
- personally identifying library information

Sanitize URLs before logging.

Never dump browser storage or session contents.

---

# Change Discipline

Before modifying playback:

- inspect existing call sites
- preserve current behavior
- keep changes narrowly scoped

Do not rewrite working systems without explicit instruction.

Keep every change incremental.

Do not commit unless explicitly requested.

When possible:

- run `./gradlew assembleDebug`
- preserve a clean working tree
- verify local playback before handing work back

---

# Decision Rule

When several solutions are technically correct:

Choose the solution that is:

- simpler
- smaller
- calmer
- easier to understand
- easier to maintain
- more consistent with the Light Phone philosophy

The interface should disappear.

The user's attention should remain on the story, not on the application.
