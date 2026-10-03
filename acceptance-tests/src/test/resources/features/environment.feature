Feature: Local acceptance environment
  Scenario: Real HTTP server and replica-set Mongo are available
    Given the local reconciliation environment is running
    And the scenario state and persistent fixtures are fresh
    When I retain scenario fixtures
    And I request an unknown API path with local credentials
    Then the HTTP status is 404
    And Mongo supports committing and rolling back transactions

  Scenario: The next scenario receives isolated state
    Given the local reconciliation environment is running
    And the scenario state and persistent fixtures are fresh
    When I request an unknown API path with local credentials
    Then the HTTP status is 404
