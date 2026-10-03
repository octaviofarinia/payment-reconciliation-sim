# HTTP access and errors

Start the API with two distinct values for `reconciliation.security.demo-token` and
`reconciliation.security.worker-token`. Each value must contain 1–1024 printable
ASCII characters without spaces. There are no default tokens. Supply secrets through
the protected runtime configuration described in the MVP specification.

Requests under `/api/v1` require `Authorization: Bearer <demo-token>`. Requests
under `/internal/v1` require `Authorization: Bearer <worker-token>`, including reads.
A missing, malformed or unknown bearer token returns 401 with `WWW-Authenticate: Bearer`;
a valid token belonging to the other role returns 403. The generator reads the demo
token from `RECONCILIATION_DEMO_TOKEN`; it has no worker access.

Open `/swagger-ui/index.html` through the API connection (the SSH tunnel for the
demo). Assets, `/v3/api-docs` and `/v3/api-docs/swagger-config` can load without a
token. Use Swagger's **Authorize** action with the demo token before executing public
operations. The OpenAPI document includes only `/api/v1/**` routes and uses the
`demoBearer` HTTP bearer security scheme. Tokens are never included in the document.

All application HTTP errors return `application/json` with `code`, `message` and
`correlationId`. The server generates a fresh UUID per request and also returns it
as `X-Correlation-Id`; client-supplied identifiers do not become error identifiers.
Error messages omit request bodies, credentials, exception details and stack traces.

| Status | Code |
| --- | --- |
| 400 | INVALID_REQUEST |
| 401 | UNAUTHORIZED |
| 403 | FORBIDDEN |
| 404 | NOT_FOUND |
| 405 | METHOD_NOT_ALLOWED |
| 406 | NOT_ACCEPTABLE |
| 409 | CONFLICT |
| 413 | PAYLOAD_TOO_LARGE |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 500 | INTERNAL_ERROR |
| 503 | SERVICE_UNAVAILABLE |

Recovery retains two distinct 409 codes: `UPLOAD_NEEDED` means “Upload needed: no
verified retained settlement input”; `ORIGINAL_UNAVAILABLE` means “Original bound
settlement version is unavailable; replacement is forbidden”. These explanations are
returned in `message` using the same JSON error contract.

`PUT /internal/v1/reconciliation-runs/{runId}/results` accepts at most 8,388,608
received body bytes. The stream is counted even without Content-Length or with
chunked transfer encoding. Larger requests return 413 before decoding or persistence.
The independent 8 MiB serialized run-document bound still applies. Result requests
use zero-based `page` (default 0) and positive `size` (default 50), capped at 100;
`outcome` optionally selects one of the five documented outcomes.
