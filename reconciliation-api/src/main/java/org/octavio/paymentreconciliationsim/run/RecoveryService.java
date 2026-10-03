package org.octavio.paymentreconciliationsim.run;

import java.util.UUID;
import org.octavio.paymentreconciliationsim.storage.SettlementStorage;
import org.octavio.paymentreconciliationsim.worker.LambdaInvoker;
import org.octavio.paymentreconciliationsim.worker.LambdaInvoker.ManualInvocation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;

@Service
public class RecoveryService {
 public static final class RecoveryConflict extends ResponseStatusException {
  public RecoveryConflict(String code,String detail) {
   super(HttpStatus.CONFLICT,detail);
   getBody().setType(java.net.URI.create("about:blank"));
   getBody().setProperty("code",code);
  }
 }
 private final RunService runs;
 private final SettlementStorage storage;
 private final LambdaInvoker invoker;

 public RecoveryService(RunService runs,SettlementStorage storage,LambdaInvoker invoker) {
  this.runs=runs; this.storage=storage; this.invoker=invoker;
 }

 public ReprocessResult reprocess(UUID runId) {
  var original=runs.metadata(runId);
  if(original.status()==Status.COMPLETED) return new ReprocessResult(false,original);
  ObjectIdentity object;
  try {
   object=storage.recoverUploadedVersion(original).orElseThrow(() ->
     new RecoveryConflict(original.objectIdentity()==null?"UPLOAD_NEEDED":"ORIGINAL_UNAVAILABLE",original.objectIdentity()==null
       ? "Upload needed: no verified retained settlement input"
       : "Original bound settlement version is unavailable; replacement is forbidden"));
  } catch(ResponseStatusException conflict) {
   throw conflict;
  } catch(RuntimeException unavailable) {
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Settlement verification temporarily unavailable");
  }
  // Bind atomically before invoking: concurrent recoveries may never choose different originals.
  runs.markProcessing(runId,object);
  var current=runs.metadata(runId);
  if(current.status()==Status.COMPLETED) return new ReprocessResult(false,current);
  try {
   invoker.invoke(new ManualInvocation(runId,object.bucket(),object.key(),object.versionId()));
  } catch(RuntimeException unavailable) {
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Worker invocation acceptance temporarily unavailable");
  }
  return new ReprocessResult(true,current);
 }
}
