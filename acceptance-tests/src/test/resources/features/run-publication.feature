Feature: Complete prepared reports publish atomically
  Background:
    Given an OPEN business date "2026-10-01"
    When I ingest the canonical purchases
    And I close purchase date "2026-10-01"
    Then the closed purchase boundary contains 4 purchases

  Scenario: Awaiting uploads never expose partial reconciliation
    When I register the prepared canonical settlement
    And I request the prepared run results
    Then the HTTP status is 409

  Scenario: Prepared canonical report includes every outcome and explicit nullable evidence
    When I register the prepared canonical settlement
    And the worker publishes the prepared canonical report
    Then the run contains the exact committed canonical business output
