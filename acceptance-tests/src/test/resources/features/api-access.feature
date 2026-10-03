@REQ-08 @REQ-09 @REQ-14 @AC-14 @AC-15
Feature: Route-based bearer access
  Scenario Outline: Protected operations require the correct role
    Given the local reconciliation environment is running
    When I call "<method>" "<path>" using "<role>" credentials with content type "application/json" and body "{}"
    Then the sanitized API error has status <status> and code "<code>"
    Examples:
      | method | path                                                                                 | role    | status | code         |
      | GET    | /api/v1/reconciliation-runs/00000000-0000-0000-0000-000000000001                        | missing | 401    | UNAUTHORIZED |
      | GET    | /internal/v1/reconciliation-runs/00000000-0000-0000-0000-000000000001/input             | wrong   | 401    | UNAUTHORIZED |
      | PUT    | /internal/v1/reconciliation-runs/00000000-0000-0000-0000-000000000001/results           | demo    | 403    | FORBIDDEN    |
      | GET    | /api/v1/reconciliation-runs/00000000-0000-0000-0000-000000000001                        | worker  | 403    | FORBIDDEN    |
  Scenario: Swagger exposes only public contracts
    Given the local reconciliation environment is running
    When I call "GET" "/v3/api-docs" using "missing" credentials with content type "application/json" and body ""
    Then the public OpenAPI document contains no worker routes
