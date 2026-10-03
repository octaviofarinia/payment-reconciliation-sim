@task8
Feature: Real uploaded settlements produce complete reconciliation
  Scenario: Canonical real-handler report and repeated bound-version event
    Given a closed reconciliation date with "canonical" purchases
    When I upload the "canonical" settlement fixture
    Then the handler publishes committed "canonical" output
    And replaying the bound event preserves the committed report

  Scenario Outline: Complete flow variants
    Given a closed reconciliation date with "<purchases>" purchases
    When I upload the "<settlement>" settlement fixture
    Then the handler publishes committed "<expected>" output

    Examples:
      | purchases | settlement           | expected             |
      | canonical | all-matched          | all-matched          |
      | empty     | canonical            | empty-internal       |
      | canonical | header-only          | header-only          |
      | empty     | header-only          | empty                |
      | canonical | duplicate-unknown    | duplicate-unknown    |
      | canonical | unequal-duplicates   | unequal-duplicates   |
      | canonical | quoted-crlf         | canonical            |
