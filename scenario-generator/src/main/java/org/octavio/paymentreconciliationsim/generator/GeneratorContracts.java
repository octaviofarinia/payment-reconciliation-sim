package org.octavio.paymentreconciliationsim.generator;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
/** Generator-owned JSON boundary; no simulator implementation types. */
public interface GeneratorContracts {
 enum ScenarioKind { CANONICAL, ALL_MATCHED }
 record Purchase(String transactionReference, String merchantId, LocalDate businessDate, long amountCentavos, String currency) {}
 record SettlementRow(int rowNumber, String reference, long amountCentavos) {}
 record Scenario(LocalDate businessDate, List<Purchase> purchases, List<SettlementRow> settlement) {}
 record Summary(int internalPurchaseCount, int settlementRowCount, int distinctSettlementReferenceCount, int totalResultCount, Map<String,Integer> outcomeCounts) {}
 record Result(String reference, String outcome, Long internalAmountCentavos, String merchantId, List<SettlementRow> settlementEvidence) {}
 record ExpectedReport(Summary summary, List<Result> results) {}
 record UploadInstructions(String url, Map<String,String> requiredHeaders) {}
 record Registration(String runId, String objectKey, String status, UploadInstructions uploadInstructions, boolean created) {}
 record Failure(String code, boolean retriable) {}
 record RunMetadata(String runId, String status, Failure error, Summary summary) {}
 record ResultPage(int page, int size, int totalResults, List<Result> results) {}
 record VerifiedRun(String runId, ExpectedReport report) {}
}
