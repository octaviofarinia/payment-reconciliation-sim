# Learning Workspace Implementation Plan

**Goal:** Build the authorized first-lesson interactive prototype faithful to option 2.

**Architecture:** One shared lesson reducer drives three views. Pure domain logic classifies immutable copied canonical fixtures; React renders the workbench and a library-rendered relationship graph. Source excerpts remain tied to the existing Java comparator.

**Tech Stack:** Product Design React/Vite starter, CSS, a graph/icon library, Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-11-learning-workspace-prototype.md`.

## Global constraints

Keep all app changes within `learning-workspace/`; preserve current backend changes. Copy the exact canonical fixtures. No real infrastructure access. State persists across modes within the browser session. Local preview only.

## Review focus

Missing counterpart evidence; duplicate precedence; switching mode/record while predictions exist; repeated/incorrect submissions; responsive and keyboard access.

## Tasks

- [x] Domain: create `src/lesson.js`, fixture copies, and `tests/lesson.test.mjs`. Export canonical `purchases`, `settlement`, `references`, `outcomes`, `classify(reference)`, `evidenceFor(reference)`, `initialState`, and `lessonReducer(state, action)`. First demonstrate failing tests, then match all expected outcomes and state transitions. Verify with `node --test tests/lesson.test.mjs`.
- [x] Workbench: implement `src/App.jsx`, focused view components, and CSS. Shared reducer actions: `mode`, `select`, `predict`, `check`, `step`, `next`, `reset`. Create functional Course/Explorer/Missions, hints, source drawer, accessible selection and completion. Install/start the official template with isolated frontend dependencies.
- [x] Verify: run all frontend tests and production build, inspect all interactions in the in-app browser, capture desktop/narrow states and compare against the exact reference. Fix actionable issues, obtain independent code review, document run instructions and `design-qa.md`, and leave preview open.

Execution note: the user explicitly requested building the selected prototype. Proceed within that authorization using parallel domain implementation and root-owned UI, without an additional approval round.
