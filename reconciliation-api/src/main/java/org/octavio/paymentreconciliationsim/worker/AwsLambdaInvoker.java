package org.octavio.paymentreconciliationsim.worker;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import tools.jackson.databind.json.JsonMapper;

/** AWS accepts an event asynchronously; never wait for the worker result here. */
public final class AwsLambdaInvoker implements LambdaInvoker {
 private final LambdaClient client;
 private final String functionName;
 private final JsonMapper json=JsonMapper.builder().build();
 public AwsLambdaInvoker(LambdaClient client,String functionName) {
  this.client=client; this.functionName=functionName;
 }
 @Override public void invoke(ManualInvocation invocation) {
  var result=client.invoke(InvokeRequest.builder().functionName(functionName)
    .invocationType(InvocationType.EVENT)
    .payload(SdkBytes.fromUtf8String(json.writeValueAsString(invocation))).build());
  if(result.statusCode()!=202) throw new IllegalStateException("Worker invocation was not accepted");
 }
}
