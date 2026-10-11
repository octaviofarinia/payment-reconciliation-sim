package org.octavio.paymentreconciliationsim.worker.domain;

import static org.octavio.paymentreconciliationsim.worker.domain.ReconciliationModel.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Pure comparison of validated inputs with unique internal purchase references. */
public final class ReconciliationComparator {
    public ComparisonReport compare(List<PurchaseInput> purchases, List<SettlementRow> settlement) {
        Map<String, PurchaseInput> internalByReference = new HashMap<>();
        for (PurchaseInput purchase : purchases) {
            internalByReference.put(purchase.reference(), purchase);
        }
        Map<String, List<SettlementRow>> settlementByReference = new HashMap<>();
        for (SettlementRow row : settlement) {
            settlementByReference.computeIfAbsent(row.reference(), ignored -> new ArrayList<>()).add(row);
        }

        var references = new TreeSet<>(internalByReference.keySet());
        references.addAll(settlementByReference.keySet());
        var counts = new EnumMap<Outcome, Integer>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0);
        }
        var results = new ArrayList<Result>();
        for (String reference : references) {
            PurchaseInput purchase = internalByReference.get(reference);
            var evidence = new ArrayList<>(settlementByReference.getOrDefault(reference, List.of()));
            evidence.sort(Comparator.comparingInt(SettlementRow::rowNumber));
            Outcome outcome = outcome(purchase, evidence);
            results.add(new Result(reference, outcome,
                    purchase == null ? null : purchase.amountCentavos(),
                    purchase == null ? null : purchase.merchantId(), evidence));
            counts.merge(outcome, 1, Integer::sum);
        }
        var summary = new Summary(purchases.size(), settlement.size(), settlementByReference.size(),
                results.size(), counts);
        return new ComparisonReport(summary, results);
    }

    private Outcome outcome(PurchaseInput purchase, List<SettlementRow> evidence) {
        if (evidence.size() > 1) {
            return Outcome.DUPLICATE;
        }
        if (purchase == null) {
            return Outcome.MISSING_INTERNALLY;
        }
        if (evidence.isEmpty()) {
            return Outcome.MISSING_IN_SETTLEMENT;
        }
        if (purchase.amountCentavos() == evidence.getFirst().amountCentavos()) {
            return Outcome.MATCHED;
        }
        return Outcome.AMOUNT_MISMATCH;
    }
}
