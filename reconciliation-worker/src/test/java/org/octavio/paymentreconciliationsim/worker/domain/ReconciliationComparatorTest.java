package org.octavio.paymentreconciliationsim.worker.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.worker.domain.ReconciliationModel.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReconciliationComparatorTest {
    private final ReconciliationComparator comparator = new ReconciliationComparator();

    private static List<PurchaseInput> canonicalPurchases() {
        return List.of(new PurchaseInput("MATCH-001", "MERCHANT-001", 10000),
                new PurchaseInput("MISS-001", "MERCHANT-001", 20000),
                new PurchaseInput("AMOUNT-001", "MERCHANT-001", 40000),
                new PurchaseInput("DUP-001", "MERCHANT-001", 50000));
    }

    private static List<SettlementRow> canonicalSettlement() {
        return List.of(new SettlementRow(1, "MATCH-001", 10000),
                new SettlementRow(2, "EXT-001", 30000),
                new SettlementRow(3, "AMOUNT-001", 45000),
                new SettlementRow(4, "DUP-001", 50000),
                new SettlementRow(5, "DUP-001", 50000));
    }

    static Stream<Arguments> canonicalResults() {
        return Stream.of(
                Arguments.of(new Result("MATCH-001", Outcome.MATCHED, 10000L, "MERCHANT-001",
                        List.of(new SettlementRow(1, "MATCH-001", 10000)))),
                Arguments.of(new Result("MISS-001", Outcome.MISSING_IN_SETTLEMENT, 20000L, "MERCHANT-001", List.of())),
                Arguments.of(new Result("AMOUNT-001", Outcome.AMOUNT_MISMATCH, 40000L, "MERCHANT-001",
                        List.of(new SettlementRow(3, "AMOUNT-001", 45000)))),
                Arguments.of(new Result("DUP-001", Outcome.DUPLICATE, 50000L, "MERCHANT-001",
                        List.of(new SettlementRow(4, "DUP-001", 50000), new SettlementRow(5, "DUP-001", 50000)))),
                Arguments.of(new Result("EXT-001", Outcome.MISSING_INTERNALLY, null, null,
                        List.of(new SettlementRow(2, "EXT-001", 30000)))));
    }

    @ParameterizedTest
    @MethodSource("canonicalResults")
    void canonicalReferenceHasExpectedOutcomeAndEvidence(Result expected) {
        var report = comparator.compare(canonicalPurchases(), canonicalSettlement());
        assertEquals(expected, report.results().stream()
                .filter(result -> result.reference().equals(expected.reference())).findFirst().orElseThrow());
    }

    @Test
    void canonicalScenarioHasFiveOutcomes() {
        var report = comparator.compare(canonicalPurchases(), canonicalSettlement());
        assertEquals(4, report.summary().internalPurchaseCount());
        assertEquals(5, report.summary().settlementRowCount());
        assertEquals(4, report.summary().distinctSettlementReferenceCount());
        assertEquals(5, report.summary().totalResultCount());
        assertEquals(Map.of(Outcome.MATCHED, 1, Outcome.MISSING_IN_SETTLEMENT, 1,
                Outcome.MISSING_INTERNALLY, 1, Outcome.AMOUNT_MISMATCH, 1, Outcome.DUPLICATE, 1),
                report.summary().outcomeCounts());
        assertEquals(1, report.summary().outcomeCounts().get(Outcome.DUPLICATE));
        assertEquals(List.of("AMOUNT-001", "DUP-001", "EXT-001", "MATCH-001", "MISS-001"),
                report.results().stream().map(Result::reference).toList());
    }

    @Test
    void emptySettlementReportsEveryInternalPurchaseAsMissing() {
        var report = comparator.compare(List.of(new PurchaseInput("B", "merchant-B", 200),
                new PurchaseInput("A", "merchant-A", 100)), List.of());
        assertEquals(new Summary(2, 0, 0, 2,
                Map.of(Outcome.MATCHED, 0, Outcome.MISSING_IN_SETTLEMENT, 2,
                        Outcome.MISSING_INTERNALLY, 0, Outcome.AMOUNT_MISMATCH, 0, Outcome.DUPLICATE, 0)),
                report.summary());
        assertEquals(List.of(new Result("A", Outcome.MISSING_IN_SETTLEMENT, 100L, "merchant-A", List.of()),
                new Result("B", Outcome.MISSING_IN_SETTLEMENT, 200L, "merchant-B", List.of())), report.results());
    }

    @Test
    void emptyPurchasesReportEverySingleSettlementReferenceAsMissingInternally() {
        var report = comparator.compare(List.of(), List.of(new SettlementRow(7, "EXT", 300)));
        assertEquals(new Summary(0, 1, 1, 1,
                Map.of(Outcome.MATCHED, 0, Outcome.MISSING_IN_SETTLEMENT, 0,
                        Outcome.MISSING_INTERNALLY, 1, Outcome.AMOUNT_MISMATCH, 0, Outcome.DUPLICATE, 0)),
                report.summary());
        assertEquals(List.of(new Result("EXT", Outcome.MISSING_INTERNALLY, null, null,
                List.of(new SettlementRow(7, "EXT", 300)))), report.results());
    }

    @Test
    void inputOrderDoesNotChangeReportOrMutateCallerLists() {
        var purchases = new ArrayList<>(canonicalPurchases().reversed());
        var rows = new ArrayList<>(canonicalSettlement().reversed());
        var originalPurchases = List.copyOf(purchases);
        var originalRows = List.copyOf(rows);
        var reordered = comparator.compare(purchases, rows);
        assertEquals(comparator.compare(canonicalPurchases(), canonicalSettlement()), reordered);
        assertEquals(originalPurchases, purchases);
        assertEquals(originalRows, rows);
        assertEquals(List.of(new SettlementRow(4, "DUP-001", 50000),
                new SettlementRow(5, "DUP-001", 50000)), reordered.results().get(1).settlementEvidence());
    }

    @Test
    void referencesRemainCaseSensitiveAndSortInNaturalStringOrder() {
        var report = comparator.compare(List.of(new PurchaseInput("a", "lower", 100)),
                List.of(new SettlementRow(1, "A", 100)));
        assertEquals(List.of(new Result("A", Outcome.MISSING_INTERNALLY, null, null,
                        List.of(new SettlementRow(1, "A", 100))),
                new Result("a", Outcome.MISSING_IN_SETTLEMENT, 100L, "lower", List.of())), report.results());
    }

    static Stream<Arguments> boundaryAmounts() {
        return Stream.of(Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, Outcome.MATCHED),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE - 1, Outcome.AMOUNT_MISMATCH),
                Arguments.of(Long.MAX_VALUE - 1, Long.MAX_VALUE, Outcome.AMOUNT_MISMATCH),
                Arguments.of(Long.MAX_VALUE, 0L, Outcome.AMOUNT_MISMATCH),
                Arguments.of(0L, Long.MAX_VALUE, Outcome.AMOUNT_MISMATCH),
                Arguments.of(0L, 0L, Outcome.MATCHED));
    }

    @ParameterizedTest
    @MethodSource("boundaryAmounts")
    void comparesAmountsExactlyWithoutOverflow(long internal, long external, Outcome expected) {
        var report = comparator.compare(List.of(new PurchaseInput("LIMIT", "merchant", internal)),
                List.of(new SettlementRow(1, "LIMIT", external)));
        assertEquals(List.of(new Result("LIMIT", expected, internal, "merchant",
                List.of(new SettlementRow(1, "LIMIT", external)))), report.results());
        assertEquals(1, report.summary().outcomeCounts().get(expected));
    }

    @Test
    void unknownDuplicatedReferenceKeepsAllUnequalEvidence() {
        var rows = List.of(new SettlementRow(9, "UNKNOWN", 200), new SettlementRow(3, "UNKNOWN", 100));
        var report = comparator.compare(List.of(), rows);
        assertEquals(1, report.summary().distinctSettlementReferenceCount());
        assertEquals(2, report.summary().settlementRowCount());
        assertEquals(1, report.summary().totalResultCount());
        assertEquals(1, report.summary().outcomeCounts().get(Outcome.DUPLICATE));
        assertEquals(List.of(new Result("UNKNOWN", Outcome.DUPLICATE, null, null,
                List.of(new SettlementRow(3, "UNKNOWN", 100), new SettlementRow(9, "UNKNOWN", 200)))),
                report.results());
    }

    @Test
    void unequalDuplicateAmountsTakePrecedenceOverAmountComparison() {
        var report = comparator.compare(List.of(new PurchaseInput("DUP", "merchant", 100)),
                List.of(new SettlementRow(1, "DUP", 100), new SettlementRow(2, "DUP", 200)));
        assertEquals(List.of(new Result("DUP", Outcome.DUPLICATE, 100L, "merchant",
                List.of(new SettlementRow(1, "DUP", 100), new SettlementRow(2, "DUP", 200)))),
                report.results());
    }

    @Test
    void bothSidesEmptyHaveAllZeroOutcomeCounts() {
        var report = comparator.compare(List.of(), List.of());
        assertEquals(0, report.summary().internalPurchaseCount());
        assertEquals(0, report.summary().settlementRowCount());
        assertEquals(0, report.summary().distinctSettlementReferenceCount());
        assertEquals(0, report.summary().totalResultCount());
        assertEquals(List.of(), report.results());
        assertEquals(5, report.summary().outcomeCounts().size());
        for (Outcome outcome : Outcome.values()) {
            assertEquals(0, report.summary().outcomeCounts().get(outcome));
        }
    }

    @Test
    void reportCollectionsAreImmutableSnapshots() {
        var purchases = new ArrayList<>(List.of(new PurchaseInput("DUP", "merchant", 100)));
        var rows = new ArrayList<>(List.of(new SettlementRow(2, "DUP", 100), new SettlementRow(1, "DUP", 200)));
        var report = comparator.compare(purchases, rows);
        purchases.clear();
        rows.clear();
        assertEquals(1, report.summary().internalPurchaseCount());
        assertEquals(2, report.summary().settlementRowCount());
        assertEquals(2, report.results().getFirst().settlementEvidence().size());
        assertThrows(UnsupportedOperationException.class, () -> report.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.results().getFirst().settlementEvidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.summary().outcomeCounts().clear());
    }

    @Test
    void domainCollectionConstructorsDefensivelyCopyTheirInputs() {
        var rows = new ArrayList<>(List.of(new SettlementRow(1, "DUP", 100)));
        var result = new Result("DUP", Outcome.DUPLICATE, 100L, "merchant", rows);
        var counts = new java.util.EnumMap<Outcome, Integer>(Outcome.class);
        counts.put(Outcome.DUPLICATE, 1);
        var summary = new Summary(1, 1, 1, 1, counts);
        var results = new ArrayList<>(List.of(result));
        var report = new ComparisonReport(summary, results);
        rows.clear();
        counts.clear();
        results.clear();
        assertEquals(List.of(new SettlementRow(1, "DUP", 100)), result.settlementEvidence());
        assertEquals(Map.of(Outcome.DUPLICATE, 1), summary.outcomeCounts());
        assertEquals(List.of(result), report.results());
        assertThrows(UnsupportedOperationException.class, () -> result.settlementEvidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.outcomeCounts().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.results().clear());
    }
}
