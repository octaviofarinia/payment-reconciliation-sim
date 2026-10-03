package org.octavio.paymentreconciliationsim.run;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
@Document("reconciliation_runs")
public record ReconciliationRun(@Id String runId,String source,String businessDate,String sha256,String rulesVersion,long byteLength,String objectKey,Status status,
 @Field(write=Field.Write.ALWAYS) ObjectIdentity objectIdentity,@Field(write=Field.Write.ALWAYS) FailureSubmission error,
 @Field(write=Field.Write.ALWAYS) ReportSubmission report,Instant createdAt,Instant updatedAt){}
