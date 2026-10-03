Feature: Simulated purchase ingestion and closed business dates
  Background:
    Given the local reconciliation environment is running
    And an OPEN business date "2026-10-01"

  Scenario: Canonical purchases form a stable closed dataset
    When I ingest the canonical purchases
    And I close purchase date "2026-10-01"
    Then the HTTP status is 200
    And the closed purchase boundary contains 4 purchases
    When I close purchase date "2026-10-01"
    Then the HTTP status is 200
    And the purchase boundary is unchanged

  Scenario: Exact replay remains harmless after closure
    When I submit purchase "SAFE-001" for date "2026-10-01" with amount 10000
    Then the HTTP status is 201
    When I close purchase date "2026-10-01"
    And I submit purchase "SAFE-001" for date "2026-10-01" with amount 10000
    Then the HTTP status is 200
    And the replayed purchase is unchanged
    When I submit purchase "SAFE-001" for date "2026-10-01" with amount 20000
    Then the HTTP status is 409
    When I submit purchase "LATE-001" for date "2026-10-01" with amount 10000
    Then the HTTP status is 409

  Scenario: Reference uniqueness applies across business dates
    Given an OPEN business date "2026-09-30"
    When I submit purchase "GLOBAL-001" for date "2026-10-01" with amount 10000
    Then the HTTP status is 201
    When I submit purchase "GLOBAL-001" for date "2026-09-30" with amount 10000
    Then the HTTP status is 409

  Scenario Outline: JSON amounts never coerce to integer centavos
    When I submit purchase "INVALID-001" for date "2026-10-01" with amount <amount>
    Then the HTTP status is 400

    Examples:
      | amount              |
      | 1.5                 |
      | 1.0                 |
      | 1e3                 |
      | 0                   |
      | -1                  |
      | 9223372036854775808  |
