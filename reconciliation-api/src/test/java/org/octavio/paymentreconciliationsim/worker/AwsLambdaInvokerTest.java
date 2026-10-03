package org.octavio.paymentreconciliationsim.worker;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.worker.LambdaInvoker.ManualInvocation;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AwsLambdaInvokerTest {
 @Test void acceptsOnly202AndSendsExactAsynchronousManualJson() {
  var client=mock(LambdaClient.class);
  var invocation=new ManualInvocation(java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
    "bucket","settlements/2026-10-01/aaaaaaaa-0000-0000-0000-000000000001.csv","original");
  var invoker=new AwsLambdaInvoker(client,"function");
  when(client.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder().statusCode(202).build());
  invoker.invoke(invocation);
  var capture=org.mockito.ArgumentCaptor.forClass(InvokeRequest.class);verify(client).invoke(capture.capture());
  var request=capture.getValue();assertEquals("function",request.functionName());assertEquals(InvocationType.EVENT,request.invocationType());
  var json=JsonMapper.builder().build();
  assertEquals(json.valueToTree(invocation),json.readTree(request.payload().asUtf8String()));
  for(int status:java.util.List.of(200,201,204,429,500)) {
   when(client.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder().statusCode(status).build());
   assertEquals("Worker invocation was not accepted",assertThrows(IllegalStateException.class,()->invoker.invoke(invocation)).getMessage());
  }
 }
}
