@REQ-08 @AC-14
Feature: Stable sanitized API errors
  Scenario Outline: Invalid requests use the same error contract
    Given the local reconciliation environment is running
    When I call "<method>" "<path>" using "demo" credentials with content type "<type>" and body "<body>"
    Then the sanitized API error has status <status> and code "<code>"
    Examples:
      | method | path                                                            | type             | body | status | code                   |
      | POST   | /api/v1/transactions                                            | application/json | {}   | 400    | INVALID_REQUEST        |
      | POST   | /api/v1/transactions                                            | text/plain       | {}   | 415    | UNSUPPORTED_MEDIA_TYPE |
      | GET    | /api/v1/reconciliation-runs/00000000-0000-0000-0000-000000000001   | application/json |      | 404    | NOT_FOUND              |
