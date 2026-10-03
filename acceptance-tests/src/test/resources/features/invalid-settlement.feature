@task8
Feature: Deterministic input errors never publish partial results
  Scenario Outline: Invalid registered bytes end without endless retries
    Given a closed reconciliation date with "canonical" purchases
    When I upload the "<fixture>" settlement fixture
    Then the handler records deterministic "<code>" without publishing

    Examples:
      | fixture        | code           |
      | invalid-header | INVALID_HEADER |
      | invalid-utf8   | INVALID_UTF8   |
