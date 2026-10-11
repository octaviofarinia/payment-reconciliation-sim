# Complete learning site implementation plan

This plan executes the approved full-site request without another approval checkpoint.

- [x] Inspect the prototype and actual API, worker, generator, infrastructure, and evidence docs.
- [x] Define eight modules / 24 lessons, source fidelity, shared state, and meaningful assessed missions.
- [x] Author source-backed curriculum, glossary, optional labs, and exact source snapshots.
- [x] Build deterministic simulation engines and mission UI with behavior tests.
- [x] Expand the shell with overview, curriculum navigation, lesson reader, persistent shared selection, responsive inspector, and resume.
- [x] Add Data / System / Code explorer lenses, search, library, bookmarks, and source dialog.
- [x] Add validated versioned persistence, deep links, export/import, and reset.
- [x] Verify source parity, state restoration, course contracts, simulation success/failure paths, and production packaging.
- [x] Run browser journeys and responsive/keyboard QA; repair findings.
- [x] Obtain independent review, resolve findings, and update usage/QA notes.
- [x] Finish the handoff: reset demonstration progress, run the last smoke check, save final screenshots, and leave the final product open.

Work is split by ownership: backend curriculum/snapshots, cloud curriculum/snapshots, simulation engine/UI/tests, and root integration/state/explorer. Existing prototype/domain tests remain in place. No backend deployment is required for the learning site.

## Verification evidence

The fresh final production build completed in 21.34 seconds and generated `index-DNhbtmgD.js`. All 37 automated tests passed with zero failures; required Sites packaging artifacts are present. The curriculum contains eight modules, 24 lessons, 25 exact source entries and 49 glossary entries.

The root agent completed all eight assessed missions through the browser, including their failure paths. Reload preserved 8/8 completion and selected reference DUP-001. Browser checks covered glossary search, bookmarks, current-module local labs, all six library sections, valid backup export/restore and invalid-format rejection without losing progress. Source Escape focus return, keyboard mode navigation, mobile curriculum navigation and mobile duplicate selection passed.

Responsive checks used 1488 × 1000 desktop, 1024 × 900 tablet and 390 × 844 mobile; mobile Code and Data views had no horizontal overflow. Observed console errors were empty. Independent review findings on escaped CSV size, editor-history coalescing, the completion CTA's stale route and currency-validation status were fixed; mobile Library labeling and scroll-to-top behavior are included in the final build. See `learning-workspace/design-qa.md` for the detailed acceptance record.

Final handoff checks passed: canceling reset preserved progress; confirming reset cleared demonstration progress to 0/8. Browser Back returned to Explorer/System and Forward returned to the overview. Final console errors remained empty. The desktop, mobile and System screenshots are saved under `learning-workspace/qa/complete-site-*.jpg`. The viewport override was reset, the browser tab was marked as the deliverable, and the fresh course overview remains visible. A valid downloaded QA progress backup retains the completed journey.
