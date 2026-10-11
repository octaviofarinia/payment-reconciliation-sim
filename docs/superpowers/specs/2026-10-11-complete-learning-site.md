# Reconciliation Lab — complete learning site

## Authorization and outcome

The user approved the dark Systems Workbench prototype and requested planning and development of the complete site. Continue directly through implementation and verification. Preserve the browser-first Course / Explorer / Missions experience for developers who know basic Java and Spring and want to understand, modify, and extend this repository.

## Product scope

Eight modules, each with three substantive lessons and an assessed browser mission:

1. Reconcile the day — shared references, five outcomes, a complete report.
2. Put an API around it — contracts, application roles, Java boundaries.
3. Make the input stable — immutable identity, business-day closure, reproducibility.
4. Cross the cloud boundary — registration, direct upload, exact object versions.
5. Build the worker — strict whole-file CSV validation, orchestration, publication.
6. Connect the system safely — network paths, IAM versus bearer roles, runtime configuration.
7. Design for failure — retryable attempts, retained input, atomic completion.
8. Own the whole system — test evidence, deployment rehearsal, incident and extension capstone.

The core course works without an account, backend, AWS account, or local Java installation. Optional source-backed local labs explain prerequisites, exact commands, and expected observations. Cloud diagrams reflect the source; real cloud deployment evidence remains pending.

## Experience

- Course overview and resume; accessible module rail and mobile navigation.
- Course, Explorer, and Missions preserve one canonical dataset and selected reference.
- Data Explorer retains all settlement evidence, including duplicate rows.
- System Explorer progressively exposes the actual client/API/Mongo/S3/Lambda architecture and a narrated request trace.
- Code Explorer offers searchable exact source snapshots with repository paths and focused line ranges.
- Each module has a distinct, deterministic lab with feedback and assessed completion: classification, request console, immutable-day workflow, delivery/version workflow, CSV validation, connection tester, failure recovery, integrated capstone.
- Search, glossary, local lab library, bookmarks, progress export/import, and explicit reset controls.
- Versioned local persistence restores valid progress and safely handles malformed data. Reading a lesson is distinct from earning mission completion.

## Implementation boundaries

Extend `learning-workspace/` in the current checkout. Keep existing backend and unrelated user edits intact. Preserve Sites worker/hosting/build packaging. Retain React/Vite, existing typography and icon package, and approved navy/blue/amber visual language. Do not introduce remote services or real credentials.

## Acceptance

All 24 lessons and eight missions are reachable. Every mission can be completed through its intended actions and rejects incorrect decisions. Cross-mode selection persists. Reload and deep links work. Export/import is validated. All source snapshots match the repository. Production build and appropriate automated tests pass. Browser checks cover a complete journey, all labs, search/source/dialog interactions, persisted progress, keyboard navigation, and desktop/mobile layouts. Independent review precedes final handoff with a running local preview.
