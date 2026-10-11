# First reconciliation lesson prototype

The user selected displayed visual option 2 (Systems Workbench) and explicitly requested implementation on 2026-10-11. Audience: basic Java/Spring knowledge, little cloud architecture. The core course is browser-first with optional local labs in the larger vision.

## Scope and visual target

Implement a self-contained React prototype in `learning-workspace/`, preserving the Java application and existing working-tree changes. Reference: [selected Systems Workbench design](../../../learning-workspace/design/reference.png).

Use its dark navy three-column workbench: compact course context, central paired ledgers/relationships, right mission inspector. Course, Explorer, and Missions are functional views sharing one immutable canonical dataset, selected reference, per-reference prediction and assessed progress. Start in Explorer on AMOUNT-001, with no fabricated completed answers. Correct the mockup's inaccurate weekday and lesson count by showing only useful fixed scenario metadata.

## Behavior

- Use the canonical four purchases and five settlement rows from the existing acceptance fixtures. Amounts remain integer centavos; format ARS at the UI boundary.
- Select any reference from either ledger or mission queue. Both ledgers, evidence, code explanation, and mission update together; duplicate settlement rows remain distinct evidence.
- Course provides three short steps: compare references, classify outcomes, understand the complete day. Step changes retain the selected reference.
- Explorer shows reference relationships, including both duplicate settlement rows. Missing references have no invented counterpart.
- Missions presents all five cases, five classification choices, evidence, graduated hint, correct/incorrect feedback, next-uncompleted action, and completion summary. Wrong answers do not advance progress. Repeated correct submission does not double count.
- Keep predictions per reference across mode changes. Switching records clears transient feedback without losing assessed completion. Reset returns the initial lesson state explicitly.
- Provide a source drawer containing the actual comparator and its source path, with keyboard dismissal and focus management.
- Keep state in memory for this prototype. No login, production backend calls, cloud calls, or claims of live AWS execution. Clearly identify browser simulation.
- Responsive layout supports narrower screens without hiding records or mission controls; native buttons/radios, visible focus, accessible tab controls, reduced motion, and meaningful text for relationships.

## Acceptance

Canonical classifications match the existing expected fixture, including duplicate precedence. Browser verification must exercise all three modes retaining selection, wrong/correct answers, all five outcomes, completion/reset, source/hint controls, and a narrow viewport. Build and domain tests must pass. Compare the implementation to the selected mock and record visual QA with screenshots.
