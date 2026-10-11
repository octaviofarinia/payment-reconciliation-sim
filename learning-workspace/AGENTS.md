# Learning Site Instructions

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact so the same local prototype can be handed to Sites. Before a Sites handoff, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.

## Learning workspace decisions

The user selected option 2, the dark Systems Workbench, for this prototype. Keep Course, Explorer, and Missions connected through one dataset and one selected reference. Use the repository's exact canonical fixtures, preserve duplicate settlement rows, and show amounts in ARS while comparing integer centavos. Target developers with basic Java/Spring knowledge and little cloud experience. Core lessons are browser-first; local labs are optional. The first lesson models pure reconciliation rules, with the real Java source available for inspection. Desktop uses a course rail, central workspace, and persistent mission panel; narrow screens stack the evidence and mission. Keep progress honest and resettable, and label simulated behavior clearly.

## Complete-site direction

The user approved the prototype's visual direction and interactions, then explicitly requested planning and developing the complete site. Preserve the workbench design while extending it to eight modules and 24 lessons, eight assessed browser missions, Data/System/Code Explorer lenses, a source and glossary library, search, bookmarks, resumable local progress, backups and optional local coding labs. Course, Explorer and Missions must continue to share the canonical dataset and selected reference across the whole site.

Keep opened-lesson counts distinct from earned mission completion. Use the versioned progress format and validate restored simulation histories. Preserve exact source snapshots and their repository paths; update parity checks when source changes. Clearly separate browser models, historical local verification, pending cloud evidence and proposed production extensions. Completion of the site does not authorize deployment of the Java/AWS project or establish that cloud verification occurred.
