# Complete-site design and interaction QA

**Final result: passed.** The fresh production build, all 37 automated tests, all eight browser missions and the reported desktop/tablet/mobile acceptance checks passed. Final screenshots are saved, demonstration progress is reset, the last smoke check passed, and the fresh course overview remains open as the deliverable.

The earlier first-lesson prototype passed its 14 tests and browser review. Those historical observations are retained below as a design baseline; they do not establish acceptance of the new curriculum, persistence, library or simulations.

## Implementation scope

- Eight modules, 24 lessons and eight assessed browser missions, plus eight optional local labs.
- Course, Explorer and Missions share the selected canonical reference and immutable canonical dataset.
- Explorer has Data, System and Code lenses. System includes progressive component disclosure and an eight-step run trace.
- The library contains 25 exact source entries and 49 glossary entries. Some source files and concepts are reused in different teaching contexts.
- Search, bookmarks, resume, hash deep links and browser history support full-course navigation.
- Version 2 localStorage persistence, validated simulation-history replay, progress JSON export/import, readable learning reports and confirmed resets replace memory-only progress.
- Library sections: Glossary, Local labs, Bookmarks, Progress, Evidence and Sources.

## Visual reference and preservation

The selected option 2 Systems Workbench mock remains `design/reference.png` at 1488 × 1058. It supplies the visual direction for the complete site, rather than a screenshot of every new screen.

| Surface | Design intent and acceptance target |
| --- | --- |
| Workbench frame | Preserve the desktop curriculum rail, central workspace and persistent contextual inspector. Narrow screens use the learning-path drawer and stacked content. |
| Typography | Continue bundled Inter with clear hierarchy between headings, evidence, controls, code and secondary notes. |
| Palette | Preserve navy surfaces, restrained borders, blue navigation/actions and amber selected-reference emphasis. Success and diagnostic feedback use semantic colors. |
| Data Explorer | Retain every canonical row, both duplicate rows and absent counterparts. Keep the large-screen graph and readable responsive ledger lists. |
| System Explorer | Add component responsibilities, boundary labels and run tracing without implying that the displayed cloud infrastructure was deployed. |
| Code Explorer | Show exact source snapshots, original repository paths, relevant focus ranges and readable code at narrow widths. |
| Course and Missions | Extend the original lesson into a coherent path. Opening a lesson must not award an assessed mission. |
| Library and search | Use the same surfaces, typography and controls. Native modal dialogs must support keyboard access, dismissal and appropriate focus return. |
| Assets | Use library icons and functional diagrams, retaining React Flow attribution. The reference PNG is design evidence, not a functional screen background. |
| Truthful copy | Replace single-module metadata with real eight-module navigation. Browser simulation, historical local proof, pending cloud observations and proposed extensions remain distinct. |

Overview, curriculum, simulation and library surfaces extend beyond the single reference image. They should preserve its visual language without reproducing illustrative progress or unsupported claims.

## Historical first-lesson baseline

The prototype review compared the reference with rendered Explorer at 1488px and checked tablet/mobile adaptations. Browser checks covered all five classifications, shared selection and predictions, duplicate evidence, wrong answers leaving progress unchanged, repeated correct answers, two hint levels, reset, keyboard mode navigation and readable source code.

Resolved prototype findings included blocked ledger clicks, overly small graph fitting after resize, missing narrow-screen reset, source lines running inline and unclear hint progression. The final adaptation used a fixed centered graph on large screens and accessible lists at narrower widths. The reported 390px mobile page had no document-level horizontal overflow.

Historical screenshots remain under ignored `qa/`:

- `desktop-before.jpg`, `desktop-after.jpg`, `desktop-final.jpg`: Explorer layout iterations.
- `course.jpg`, `completed-course.jpg`, `duplicate-explorer.jpg`: lesson and record states.
- `mobile-course.jpg`, `mobile-missions.jpg`, `mobile-missions-final.jpg`, `mobile-explorer-final.jpg`: narrow layouts.
- `desktop-source-final.jpg`, `mobile-source-final.jpg`: source readability.
- `tablet-final.jpg`: tablet ledger-list presentation.

These are earlier prototype evidence. Fresh complete-site captures and comparisons belong in the final acceptance record.

## Automated verification

The root agent's fresh final production build completed successfully in **21.34 seconds**, emitting `index-DNhbtmgD.js`. The complete suite passed **37/37 tests, with zero failures**. Required Sites artifacts are present: `dist/client/index.html`, `dist/server/index.js` and `dist/.openai/hosting.json`.

| Suite | Coverage | Final run |
| --- | --- | --- |
| Canonical lesson | Fixture/source parity, duplicate precedence, missing records, immutable inputs, selection/predictions, classification progress and reset | Passed |
| Simulations | Exact CSV metadata, API contracts, purchase replay, closed-day writes, original binding, strict CSV rejection, security layers, recovery and capstone gates | Passed |
| Progress | Reading versus assessment, cross-module state, persistence, reset, invalid imports, routes, maximum-sized escaped CSV restoration and editor-history coalescing | Passed |
| Curriculum | Eight modules/24 lessons, valid references, 25 byte-exact snapshots, focus ranges, missions/local labs and complete search indexing | Passed |
| Sites worker | Static assets, application fallback, API/write-request handling and required build artifacts | Passed |

This record reports the root agent's observed build, test and browser results. Its documentation author did not independently rerun that final integrated suite.

## Complete-site browser acceptance

The root agent exercised the complete site through browser controls. All eight assessed missions reached completion through their required actions; completion was not injected into state.

| Area | Acceptance scenario | Status |
| --- | --- | --- |
| Course navigation | All eight modules reached through their missions. The repaired “See complete day” CTA navigated to `#/course/reconciliation/3`. Mobile drawer exposed all eight modules and navigation worked. | Passed |
| Shared state | DUP-001 remained selected after reload with 8/8 missions complete. Mobile ledger selection preserved both duplicate rows. | Passed |
| Assessments | All eight missions completed with the rejection paths listed below. Automated progress tests separately prove that opening lessons awards no mission. | Passed |
| Code/source | Source expansion worked; Escape returned focus to the Expand control. Mobile Code remained readable without page-level horizontal overflow. | Passed |
| Search | Global search for “Idempotency” opened the filtered glossary. Automated indexing checks cover every published lesson, mission, glossary and source target. | Passed |
| Bookmarks | A saved lesson appeared in Library → Bookmarks. | Passed |
| Browser history | Back returned to Explorer/System; Forward returned to the overview. | Passed |
| Persistence | Reload retained all eight assessed completions and selected reference DUP-001. | Passed |
| Export/import | Progress JSON downloaded and restored successfully. Invalid-format import was rejected in the UI while preserving 8/8 completion. | Passed |
| Library | All six sections opened. Local labs showed the current module and its instructions. | Passed |
| Reset | Canceling confirmation preserved progress. Confirming reset cleared all demonstration progress to 0/8. The valid QA backup remains downloaded. | Passed |
| Responsive | Checked 1488 × 1000 desktop, 1024 × 900 tablet and 390 × 844 mobile. Mobile document client width was 375px because of the scrollbar; Code and Data views had no horizontal overflow. The Library icon's accessible label was verified on mobile. | Passed |
| Keyboard | Arrow-key mode navigation worked. Source Escape dismissal restored focus correctly. | Passed |
| Runtime | Observed console errors were `[]`. Production build and Sites packaging passed. | Passed |

### Mission journeys actually exercised

| Mission | Observed failure and recovery path |
| --- | --- |
| Reconcile the day | An incorrect classification did not award completion; the five canonical references were then classified correctly. |
| API contracts | A wrong-role request was rejected, followed by the valid required public workflow. |
| Stable input | Immutable replay/conflict behavior and a late write to the closed day were exercised. |
| Cloud boundary | An expired PUT was rejected, and attempted replacement could not change the bound original. |
| Worker | Malformed CSV was rejected; repairing it produced the complete canonical report. |
| Security | Blocked routing, worker-to-Atlas access and public API access were tested alongside the required permitted paths. |
| Recovery | Original-unavailable recovery failed; recovery with the original succeeded; completed replay retained one publication. |
| Capstone | An incorrect result count and incorrect extension decision were rejected before the full lifecycle and final assessment passed. |

### Findings resolved before the final build

- Progress restoration now allows the serialized size overhead of a maximum-length CSV edit while retaining the editor's actual input limit.
- Consecutive editor configuration events are coalesced, preventing normal typing from exhausting the action-history bound while preserving inputs around assessed commands.
- The first mission's completion CTA no longer uses stale routing state. Its complete-day destination was verified in the browser.
- Invalid currency uses the request-validation status 400 rather than an immutable-conflict status 409.
- The mobile Library icon has an accessible label, and mobile navigation resets scrolling to the intended content position.

The changes were included in the fresh build identified above. These fixes extend the historical prototype review; they do not replace the reported full-site mission journeys.

## Final acceptance record

Implementation, integrated verification and final handoff passed. The final capture set is saved under `qa/`:

- `complete-site-home.jpg`: fresh desktop overview.
- `complete-site-mobile.jpg`: mobile learning workspace.
- `complete-site-system.jpg`: System Explorer.

File existence was checked after capture. The completed 8/8 QA journey remains in a downloaded backup; the visible site has been reset to a clean 0/8 starting state. The viewport override was removed, the tab was marked as the deliverable, and the course overview remains open. The final console error list was empty.

The site's limits are intentional: progress is browser-local, all core execution is simulated, local Java verification is separate, and actual cloud evidence remains pending in project documentation. New full-site screens extend the approved visual language beyond the single lesson mock. No claim of backend cloud deployment or certification is made.
