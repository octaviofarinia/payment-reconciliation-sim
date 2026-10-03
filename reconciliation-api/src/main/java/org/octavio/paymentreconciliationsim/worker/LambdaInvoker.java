package org.octavio.paymentreconciliationsim.worker;
import java.util.UUID;
/** Accept an asynchronous invocation; completion is recorded through worker callbacks. */
public interface LambdaInvoker {
 void invoke(ManualInvocation invocation);
 record ManualInvocation(UUID runId,String bucket,String key,String versionId) {}
}
