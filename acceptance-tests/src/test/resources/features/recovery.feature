Feature: Manual recovery preserves the original run and immutable settlement version
  Scenario: A retained upload whose event was lost is recovered asynchronously
    Given a closed reconciliation date with "empty" purchases
    When I retain a header-only upload without delivering its event
    And I request manual recovery with HTTP 202
    Then recovery publishes one completed report for the retained version
    When I request manual recovery with HTTP 200
    Then recovery publishes one completed report for the retained version

  Scenario: Completed recovery leaves committed output unchanged
    Given a closed reconciliation date with "canonical" purchases
    When I upload the "canonical" settlement fixture
    Then the handler publishes committed "canonical" output
    When I request manual recovery with HTTP 200
    Then the handler publishes committed "canonical" output
