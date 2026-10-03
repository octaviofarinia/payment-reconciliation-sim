@generator
Feature: Isolated scenario generator
  Scenario: Actual CLI verifies the canonical report through public contracts
    Given the local reconciliation environment is running
    When the actual canonical generator CLI runs through public APIs
    Then the generator exits successfully and verifies exact business results
