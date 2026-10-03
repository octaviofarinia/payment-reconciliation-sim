package org.octavio.paymentreconciliationsim.worker;
import com.amazonaws.services.lambda.runtime.*;
import java.time.Instant;
import java.util.*;
import org.octavio.paymentreconciliationsim.worker.event.InvocationParser;
/** Deployable entry point also used by local asynchronous uploads. */
public final class ReconciliationHandler implements RequestHandler<Map<String,Object>,Void> {
 private final ProcessingService processing;
 private final InvocationParser parser = new InvocationParser();
 public ReconciliationHandler() { this(ProcessingService.cloud(System.getenv())); }
 public ReconciliationHandler(ProcessingService processing) { this.processing = processing; }
 @Override public Void handleRequest(Map<String,Object> event,Context context) {
  String requestId = context == null ? null : context.getAwsRequestId();
  var attempt = new AttemptContext(requestId == null ? UUID.randomUUID().toString() : requestId,Instant.now());
  boolean retry = false;
  for (var invocation : parser.parse(event)) {
   if (invocation.error() != null) log(context,attempt,invocation.reference(),invocation.error());
   else {
    try { processing.process(invocation.reference(),attempt); }
    catch (InputRejected rejected) { log(context,attempt,invocation.reference(),rejected.code()); }
    catch (RuntimeException failure) { retry = true; log(context,attempt,invocation.reference(),"RETRY_REQUIRED"); }
   }
  }
  if (retry) throw new IllegalStateException("Worker invocation failed; retry required");
  return null;
 }
 private static void log(Context context,AttemptContext attempt,org.octavio.paymentreconciliationsim.worker.event.ObjectReference reference,String code) {
  String message = WorkerDiagnostics.message(reference,null,attempt,code) + "\n";
  if (context == null) System.out.print(message);
  else context.getLogger().log(message);
 }
}
