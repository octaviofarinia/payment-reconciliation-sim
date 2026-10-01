# Spec Driven Development Workflow

This repository uses a reviewed specification as the behavioral baseline for the Payment Reconciliation Platform. The purpose is to keep a one-week project focused and make every feature demonstrable through acceptance evidence.

## Documents and authority

The [MVP specification](superpowers/specs/2026-10-01-payment-reconciliation-design.md) contains numbered REQ requirements, contracts, architecture decisions, and AC acceptance criteria. Its status is Draft until the owner reviews it. Proposed defaults are distinguished from decisions already made in the conversation.

After that review, create an implementation plan in docs/superpowers/plans. Each task identifies the requirement IDs it implements, the acceptance criteria it verifies, the affected components, and its verification command or manual procedure. Do not treat the current delivery-order paragraph as a completed implementation plan.

The implemented OpenAPI document must match the reviewed REST contract. Expected scenario fixtures must match the classification rules. When those disagree, reconcile them through an explicit specification change rather than allowing accidental behavior to become the new requirement.

## Working through a task

1. Select one planned task and read its referenced requirements and acceptance criteria.
2. Express the observable behavior as focused tests or a reproducible verification procedure. Give automated tests names or comments identifying the relevant AC IDs.
3. Implement the smallest complete behavior that satisfies that task.
4. Run its checks and inspect the result. Add integration checks where database atomicity or HTTP failure behavior matters.
5. Record the evidence, relevant limitations, and any spec changes in the task's pull request or acceptance record.
6. Proceed to the next task only after the current behavior and its checks agree.

Keep spec, code, fixtures, and acceptance evidence together in the same change when modifying behavior. Record consequential architecture changes with the reason and tradeoff; do not silently expand scope or add AWS services.

## Review and release evidence

A feature is complete when its acceptance criteria are demonstrated. A green application startup or generic context test does not establish reconciliation correctness.

Before the portfolio demonstration, record a compact table of AC ID, test/scenario, command or procedure, observed outcome, and artifact location. Include the five-outcome scenario, duplicate-event replay, date-close race, API outage recovery, real S3-trigger execution, maximum-size timing, and teardown evidence. Do not publish credentials, presigned URLs, card data, or unredacted sensitive logs.

The local demonstration remains useful if cloud credits expire. The AWS smoke test proves the integration separately; local Testcontainers or HTTP mocks do not substitute for it.
