# Learning Site Instructions

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable design feedback, preferences, or decisions, record them in `AGENTS.md`.

Use `design/reference.png` as the visual reference for layout, component anatomy, density, spacing, color, typography and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact for Sites hosting. Before publishing to Sites, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.

## Design and audience

The site uses the dark Systems Workbench design. Keep Course, Explorer, and Missions connected through one dataset and one selected reference. Use the repository's exact canonical fixtures, preserve duplicate settlement rows, and show amounts in ARS while comparing integer centavos. Target developers with basic Java/Spring knowledge and little cloud experience. Core lessons are browser-first; local labs are optional. The first lesson models pure reconciliation rules, with the real Java source available for inspection. Desktop uses a course rail, central workspace, and persistent mission panel; narrow screens stack the evidence and mission. Keep progress honest and resettable, and label simulated behavior clearly.

## Course structure

Preserve the workbench design, eight modules and 24 lessons, eight assessed browser missions, Data/System/Code Explorer lenses, source and glossary library, search, bookmarks, resumable local progress, backups and optional local coding labs. Course, Explorer and Missions must continue to share the canonical dataset and selected reference across the whole site.

Keep opened-lesson counts distinct from earned mission completion. Use the versioned progress format and validate restored simulation histories. Preserve exact source snapshots and their repository paths; update parity checks when source changes. Label browser simulations and proposed extensions clearly. The backend deployment and generator simulation have been verified on AWS; browser mission completion is a separate learning activity. Deployment and benchmark procedures live in `../docs/`.
