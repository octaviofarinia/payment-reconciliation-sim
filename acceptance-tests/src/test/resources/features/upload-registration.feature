Feature: Upload instructions preserve registered input and immutable versions
  Background:
    Given an OPEN business date "2026-10-01"
    When I close purchase date "2026-10-01"

  Scenario: Registered bytes upload through the returned instructions and retain first validated version
    When I register the three-byte settlement input
    Then the upload instructions declare the exact checksum and bounded expiry
    When I upload registered bytes twice and reject altered bytes
    Then uploadChecksumAndBoundVersionAreStable

  Scenario: Lost events recover only retained registered bytes
    When I register the three-byte settlement input
    Then no matching retained version is recoverable
