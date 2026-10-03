Feature: Deliberate lifecycle failure probe
  Scenario: A failing HTTP assertion still closes every process
    Given the local reconciliation environment is running
    When I request an unknown API path with local credentials
    Then the HTTP status is 418
