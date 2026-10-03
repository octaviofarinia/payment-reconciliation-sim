package org.octavio.paymentreconciliationsim.worker;

import java.time.Duration;
import java.time.Instant;
import org.octavio.paymentreconciliationsim.worker.event.ObjectReference;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient.Metadata;
import tools.jackson.databind.json.JsonMapper;

/** Emit only stable correlation fields; never include exception messages or transport bodies. */
public final class WorkerDiagnostics {
 private WorkerDiagnostics() {}
 private record Diagnostic(String runId,String businessDate,String versionId,String rulesVersion,
                           String attemptId,long durationMillis,String errorCode) {}
 public static String message(ObjectReference reference,Metadata metadata,AttemptContext attempt,String code) {
  return JsonMapper.builder().build().writeValueAsString(new Diagnostic(
    reference==null?null:reference.runId(),metadata==null?null:metadata.businessDate(),
    reference==null?null:reference.versionId(),metadata==null?null:metadata.rulesVersion(),
    attempt.attemptId(),Math.max(0,Duration.between(attempt.startedAt(),Instant.now()).toMillis()),code));
 }
}
