package org.octavio.paymentreconciliationsim.run;
import java.time.*;
import java.util.*;
import org.octavio.paymentreconciliationsim.storage.SettlementStorage.UploadInstructions;
/** API-owned wire values. The worker owns matching JSON contracts independently. */
public final class RunContracts {
 private RunContracts(){}
 public enum Status { AWAITING_UPLOAD, PROCESSING, COMPLETED, FAILED }
 public enum Outcome { MATCHED, MISSING_IN_SETTLEMENT, MISSING_INTERNALLY, AMOUNT_MISMATCH, DUPLICATE }
 public record RegisterRun(LocalDate businessDate,String sha256,long byteLength){}
 public record ObjectIdentity(String bucket,String key,String versionId,String sha256){}
 public record InputIdentity(String source,String businessDate,String sha256,String rulesVersion,ObjectIdentity objectIdentity){}
 public record PurchaseInput(String reference,String merchantId,long amountCentavos){}
 public record SettlementRow(int rowNumber,String reference,long amountCentavos){}
 public record Result(String reference,Outcome outcome,@org.springframework.data.mongodb.core.mapping.Field(write=org.springframework.data.mongodb.core.mapping.Field.Write.ALWAYS) Long internalAmountCentavos,@org.springframework.data.mongodb.core.mapping.Field(write=org.springframework.data.mongodb.core.mapping.Field.Write.ALWAYS) String merchantId,List<SettlementRow> settlementEvidence){}
 public record Summary(int internalPurchaseCount,int settlementRowCount,int distinctSettlementReferenceCount,int totalResultCount,Map<Outcome,Integer> outcomeCounts){}
 public record ReportSubmission(InputIdentity inputIdentity,Summary summary,List<Result> results){}
 public record FailureSubmission(String code,boolean retriable,String attemptId){}
 public record RunMetadata(UUID runId,String source,String businessDate,String sha256,String rulesVersion,long byteLength,String objectKey,Status status,ObjectIdentity objectIdentity,FailureSubmission error,Summary summary,Instant createdAt,Instant updatedAt,boolean recoveryNeeded){}
 public record RunInput(UUID runId,String source,String businessDate,String sha256,String rulesVersion,ObjectIdentity objectIdentity,List<PurchaseInput> purchases){}
 public record RegistrationResult(UUID runId,String objectKey,Status status,UploadInstructions uploadInstructions,boolean created){}
 public record ReprocessResult(boolean accepted,RunMetadata run){}
 public record PublicationResult(boolean published,RunMetadata run){}
 public record ResultPage(int page,int size,long totalResults,List<Result> results){}
}
