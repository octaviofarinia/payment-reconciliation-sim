package org.octavio.paymentreconciliationsim.worker.domain;

import java.util.List;
import java.util.Map;

/** Worker-owned domain values; input validation belongs to the ingestion boundary. */
public final class ReconciliationModel {
    private ReconciliationModel() {}

    public enum Outcome {
        MATCHED, MISSING_IN_SETTLEMENT, MISSING_INTERNALLY, AMOUNT_MISMATCH, DUPLICATE
    }

    public record PurchaseInput(String reference, String merchantId, long amountCentavos) {}

    public record SettlementRow(int rowNumber, String reference, long amountCentavos) {}

    public record Result(String reference, Outcome outcome, Long internalAmountCentavos,
                         String merchantId, List<SettlementRow> settlementEvidence) {
        public Result {
            settlementEvidence = List.copyOf(settlementEvidence);
        }
    }

    public record Summary(int internalPurchaseCount, int settlementRowCount,
                          int distinctSettlementReferenceCount, int totalResultCount,
                          Map<Outcome, Integer> outcomeCounts) {
        public Summary {
            outcomeCounts = Map.copyOf(outcomeCounts);
        }
    }

    public record ComparisonReport(Summary summary, List<Result> results) {
        public ComparisonReport {
            results = List.copyOf(results);
        }
    }
}
