# Design and interaction checks

Reconciliation Lab uses a dark workbench with a course rail, central workspace and
contextual mission panel. [design/reference.png](design/reference.png) is the
visual reference for the first lesson. The eight-module course extends that
layout to the overview, simulations, source viewer, library and progress tools.

## Layout and accessibility

| Surface | Expected behavior |
| --- | --- |
| Workbench | Desktop shows the curriculum rail and mission panel. Narrow screens use a learning-path drawer and stacked content. |
| Typography and color | Bundled Inter, navy surfaces, blue navigation/actions and amber selection. Success and diagnostic states use semantic colors and text. |
| Data Explorer | All canonical rows remain visible, including both duplicate rows and missing counterparts. Large screens use the relationship graph; narrow screens use ledger lists. |
| System Explorer | Component responsibilities and request tracing describe the application architecture. The browser makes no backend or cloud calls. |
| Code Explorer | Exact snapshots show repository paths and focused line ranges. Long code lines scroll within the code panel. |
| Course and Missions | Opening a lesson records reading activity. Mission completion requires the assessed actions. |
| Library and search | Dialogs support keyboard navigation, Escape dismissal and focus return. Icon-only controls have accessible labels. |
| Motion and assets | Respect reduced-motion settings. Preserve React Flow attribution. The reference image is a design asset. |

Course, Explorer and Missions share the canonical dataset and selected reference.
Amounts are stored and compared as integer centavos and displayed as ARS. The
library distinguishes browser activities, local tests, AWS validation and extension
exercises.

## Automated checks

From `learning-workspace/`:

~~~sh
npm run build
npm test
~~~

Run the build first because the Sites worker suite inspects generated artifacts.
The build produces `dist/client/index.html`, `dist/server/index.js` and
`dist/.openai/hosting.json`.

| Suite | Checks |
| --- | --- |
| Canonical lesson | Fixture/source parity, duplicate precedence, missing records, immutable inputs, selection, predictions, classification progress and reset |
| Simulations | CSV metadata, API contracts, purchase replay, closed-day writes, original binding, whole-file validation, access layers, recovery and capstone decisions |
| Progress | Reading versus assessment, persistence, restore validation, routes, maximum-sized escaped CSV and editor-history coalescing |
| Curriculum | Eight modules, 24 lessons, source/concept references, 25 byte-exact snapshots, focus ranges, missions, local labs and search indexing |
| Sites worker | Static assets, application fallback, request handling and required build artifacts |

## Browser verification

Start `npm run dev` or preview a production build with `npm run preview`. Use the
interface to complete each mission, including the rejection paths below.

| Mission | Checks |
| --- | --- |
| Reconcile the day | Reject an incorrect classification; classify all 100 references correctly; open the complete-day lesson. |
| API contracts | Reject a wrong-role request and complete the valid public workflow. |
| Stable input | Exercise immutable replay, conflicting input and a late write to the closed date. |
| Cloud boundary | Reject an expired PUT and protect the bound original from replacement. |
| Worker | Reject malformed CSV, repair it and publish the complete canonical report. |
| Security | Check blocked routes, worker-to-Atlas access and caller roles alongside permitted paths. |
| Recovery | Reject recovery when the original is unavailable; restore it, recover and replay without another publication. |
| Capstone | Reject an incorrect result count and extension decision, then complete the lifecycle and assessment. |

Check navigation between all modules and modes. Keep DUP-001 selected across
reload and confirm that both settlement rows remain visible. Verify source
expansion, Escape focus return, glossary search, bookmarks, local labs and all six
library sections.

Export progress, restore the valid JSON, and reject a malformed import without
losing current progress. Confirm that canceling reset preserves state and a full
reset clears missions, lesson history and bookmarks. Browser Back/Forward and hash
deep links should restore the intended view.

Check desktop at 1488 × 1000, tablet at 1024 × 900 and mobile at 390 × 844. Data
and Code views must remain usable without document-level horizontal overflow.
Verify keyboard mode navigation, mobile curriculum navigation, visible focus and
accessible control labels. Inspect the browser console for runtime errors.
