# Reconciliation Lab

A complete React/Vite learning site for developers who know basic Java and Spring and want to understand, modify and extend this payment reconciliation project. The selected dark Systems Workbench design connects three modes: **Course** explains each layer, **Explorer** connects its evidence and components, and **Missions** assesses it through browser interactions.

The site contains **8 modules, 24 lessons and 8 assessed browser missions**, with an optional local coding lab for every module. Explorer offers **Data, System and Code** lenses. The library includes **25 source entries with exact project snapshots** and **49 glossary entries**; some project files and concepts appear in more than one teaching context.

## Use the course online

Open [Reconciliation Lab on GitHub Pages](https://octaviofarinia.github.io/payment-reconciliation-sim/). The entire core course, all Explorer lenses, missions and source snapshots are available without cloning the repository or installing anything. Progress is saved in your browser; use **Library → Progress** to export a backup.

## Learning path

| Module | Focus | Browser practice |
| --- | --- | --- |
| 01 · Reconcile the day | Integer money, reference matching and five outcomes | Classify the five canonical references and retain every evidence row |
| 02 · Put an API around it | HTTP contracts, Spring boundaries and caller roles | Sequence valid requests and diagnose method and role errors |
| 03 · Make the input stable | Immutable purchases, replay and business-day closure | Replay, conflict, close and attempt a late write |
| 04 · Cross the cloud boundary | Registered metadata, checksums, uploads and object versions | Deliver the correct bytes and protect the original input |
| 05 · Build the worker | Strict ingestion, pure comparison and complete publication | Reject malformed CSV and repair it before publishing |
| 06 · Connect the system safely | Network paths, IAM and API authorization | Diagnose each access layer independently |
| 07 · Design for failure | Failed attempts, retained originals and idempotent effects | Restore access, recover the original and replay completion |
| 08 · Own the whole system | Independent verification, deployment rehearsal and extension design | Complete the lifecycle, audit the report and choose affected boundaries |

The shared canonical reference follows navigation between modes, modules and Explorer lenses. Selecting a duplicate keeps both settlement rows; missing counterparts remain absent. A System trace follows a complete run across component boundaries while keeping the selected reference visible. Code exploration opens the actual source with relevant line ranges.

Global search covers lessons, missions, glossary and sources; `Ctrl+K` or `Cmd+K` opens it. Lessons can be bookmarked and resumed. Hash routes support direct links and browser history, for example `#/course/recovery/2`, `#/explorer/security/system` and `#/missions/capstone`.

## Progress and data

Assessed mission completion and lessons opened are distinct metrics. Reading all 24 lessons does not complete any mission. Incorrect classifications do not advance progress, and repeated correct answers count once. A mission reset affects only that module; resetting the full journey also clears lesson history and bookmarks after confirmation.

Progress is saved in this browser origin's `localStorage` under `reconciliation-lab:v2`. The version 2 format stores navigation, selected reference, predictions, classifications, opened lessons, bookmarks and simulation action histories. Restoration validates format and bounds, sanitizes known records and lesson keys, and reconstructs simulation states by replaying their actions. This is a personal learning record, not an authentication or certification mechanism.

Library → Progress exports and restores a JSON backup and downloads a readable learning report. Import rejects unsupported or malformed files without replacing current progress. If browser storage is unavailable or full, the site reports that limitation and offers export; it does not synchronize across devices or accounts.

`src/data/` contains exact copies of the canonical fixtures in `../acceptance-tests/src/test/resources/fixtures/`: four purchases, five settlement rows and the independent expected report. Amounts remain integer centavos until display. CSV editing belongs to the worker simulation and does not rewrite these canonical fixtures.

`src/content/course.js`, `backend.js` and `cloud.js` define the curriculum, glossary and source links. `src/reference/` contains full source snapshots. The curriculum tests compare all 25 displayed snapshots byte-for-byte with the actual repository files, validate focus ranges and resolve every published source and concept reference. Refresh a snapshot deliberately when its original project file changes.

## Run the course from a fresh clone

Install **Node.js 22.12 or newer and npm**, then clone this repository. The core course runs entirely in your browser: Java, Maven, Docker, a running backend and AWS/Atlas accounts are not required. Optional local coding labs list their own prerequisites.

From the cloned **repository root**, run these commands in your terminal (Linux, macOS or Windows):

```sh
cd learning-workspace
npm ci
npm run build
npm run preview
```

Open the URL printed by Vite, normally [http://localhost:4173/](http://localhost:4173/). Start with **Course → Reconcile the day**, switch to **Explorer** to inspect the same records, and complete the matching **Mission**. No login or configuration file is needed. Stop the server with `Ctrl+C` when finished; use `npm run preview` again to reopen the generated site.

`npm ci` installs the versions in the committed `package-lock.json`. The build generates `dist/`, which is intentionally excluded from Git along with `node_modules/`. After pulling site changes, run `npm ci` and `npm run build` again before previewing. Serve the build through Vite rather than opening its HTML file directly.

Progress belongs to the browser and origin you use. Keep the same hostname and port to resume automatically; use **Library → Progress** to export a backup before switching browsers, addresses or machines.

## Development and verification

To edit the site with automatic reload, run this from `learning-workspace/` after installing dependencies:

```sh
npm run dev
```

Open the URL printed by Vite, normally [http://localhost:5173/](http://localhost:5173/). To verify the site, also from `learning-workspace/`:

```sh
npm run build
npm test
```

`npm test` runs the canonical lesson, simulation, progress, curriculum and Sites worker suites. Run the build before the full suite because Sites packaging tests inspect generated artifacts. `npm run test:lesson` and `npm run test:sites` run those focused suites separately. The build writes the client to `dist/client/` and prepares `dist/server/index.js` and `dist/.openai/hosting.json` for a Sites handoff. No publish or cloud deployment happens during this build.

### Windows and WSL

For a checkout inside WSL, prefer running Linux Node and npm from a WSL terminal with the same commands above. A regular Windows checkout also uses the commands above directly.

When using native Windows Node against a WSL UNC checkout, npm's command shell may require a temporary drive mapping. In **Command Prompt**, replace the example path below with the actual path to your checkout; `pushd` maps it and `popd` releases it after stopping Vite:

```bat
pushd "\\wsl.localhost\Ubuntu\home\YOUR_USER\projects\payment-reconciliation-sim\learning-workspace"
set CHOKIDAR_USEPOLLING=true
npm ci
npm run dev
rem Stop Vite with Ctrl+C, then run:
popd
```

The polling setting helps Vite observe changes over the UNC share. Build and test commands can run from the same mapped directory.

## GitHub Pages deployment

[Publish learning site](../.github/workflows/learning-site-pages.yml) builds and tests the course on pushes to `master`, then deploys `dist/client/` using GitHub's Pages actions. The workflow can also be run manually from the repository's Actions tab. In **Settings → Pages**, the publishing source must be **GitHub Actions**.

The workflow reads the site's base path from GitHub Pages and passes it through `VITE_BASE_PATH`, so JavaScript, CSS, fonts and the favicon load under `/payment-reconciliation-sim/`. Local development and ordinary builds default to `/`. To reproduce the public build from `learning-workspace/`, set the variable for your shell:

```sh
# Linux, macOS or WSL
VITE_BASE_PATH=/payment-reconciliation-sim/ npm run build
npm test
npm run preview
```

```powershell
# PowerShell
$env:VITE_BASE_PATH = '/payment-reconciliation-sim/'
npm run build
npm test
npm run preview
```

Open the preview at `/payment-reconciliation-sim/`. Existing hash links, such as `/payment-reconciliation-sim/#/course/recovery/2`, work when shared or refreshed. Generated files remain excluded from Git. GitHub Pages serves the static client; the Sites server package remains available for a separate Sites handoff.

Deployment requires every test to pass, including snapshot parity with the Java and Terraform sources. When those project files change, update the teaching snapshots deliberately before deploying the updated course. Workflow failures are visible in **Actions → Publish learning site**; the previous successful deployment stays available.

## Evidence and limits

This site is a browser simulation with local persistence. It has no login, hosted progress service, production backend calls or cloud execution. The source viewer explains Java and Terraform; the browser does not execute them. Optional local labs provide commands, prerequisites and expected evidence for the actual project, and are not required for core course completion.

The repository documents a historical local Java verification checkpoint. Those results are not a fresh verification caused by opening this course. Actual Terraform validation, AWS delivery and authorization, Atlas startup, cloud runtime measurements, account eligibility and teardown remain pending in the project documentation. The Evidence section keeps those boundaries visible. Capstone queues, larger report storage and new outcomes are proposed extensions, not implemented backend features.

See `design-qa.md` for visual and interaction acceptance evidence for this site. The backend and infrastructure remain separate from the learning frontend.
