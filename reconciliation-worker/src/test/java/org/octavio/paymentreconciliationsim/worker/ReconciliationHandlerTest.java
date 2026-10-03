package org.octavio.paymentreconciliationsim.worker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.amazonaws.services.lambda.runtime.*;
import org.octavio.paymentreconciliationsim.worker.event.*;
import java.util.*;
class ReconciliationHandlerTest {
 final ProcessingService processing=mock(ProcessingService.class);final ReconciliationHandler handler=new ReconciliationHandler(processing);
 static final String ID="12345678-1234-1234-1234-123456789abc",KEY="settlements/2026-10-01/"+ID+".csv";
 static Map<String,Object> event(){return Map.of("runId",ID,"bucket","bucket","key",KEY,"versionId","v1");}
 static Map<String,Object> record(String id){return Map.of("eventSource","aws:s3","s3",Map.of("bucket",Map.of("name","bucket"),"object",Map.of("key","settlements/2026-10-01/"+id+".csv","versionId","v1")));}
 @Test void unsupportedInvocationEmitsCorrelatedDiagnostic() {
  var context=mock(Context.class);var logger=mock(LambdaLogger.class);
  when(context.getAwsRequestId()).thenReturn("attempt-1");when(context.getLogger()).thenReturn(logger);
  handler.handleRequest(Map.of("unexpected",true),context);
  verify(logger).log("attemptId=attempt-1 record=0 code=INVALID_INVOCATION\n");verifyNoInteractions(processing);
 }
 @Test void invocationWithoutLambdaContextStillRejectsSafely(){assertNull(handler.handleRequest(Map.of(),null));verifyNoInteractions(processing);}
 @Test void deployableNoArgEntrypointBuildsCloudServiceAndProcessesManualInvocation(){
  try(var factory=mockStatic(ProcessingService.class)){
   factory.when(()->ProcessingService.cloud(anyMap())).thenReturn(processing);
   assertNull(new ReconciliationHandler().handleRequest(event(),null));
   var cap=org.mockito.ArgumentCaptor.forClass(AttemptContext.class);verify(processing).process(eq(new ObjectReference(ID,"bucket",KEY,"v1")),cap.capture());
   assertNotNull(cap.getValue().startedAt());assertDoesNotThrow(()->UUID.fromString(cap.getValue().attemptId()));
  }
 }
 @Test void deterministicRejectionEndsNormallyWithCorrelatedCode(){
  var context=mock(Context.class);var logger=mock(LambdaLogger.class);when(context.getAwsRequestId()).thenReturn("attempt");when(context.getLogger()).thenReturn(logger);
  doThrow(new InputRejected("UNREGISTERED_OBJECT")).when(processing).process(any(),any());
  assertNull(handler.handleRequest(event(),context));verify(logger).log("attemptId=attempt record=0 code=UNREGISTERED_OBJECT\n");
 }
 @Test void batchContinuesAllRecordsThenFailsForRetryWithoutLeakingTransportDetails(){
  String other="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  doThrow(new IllegalStateException("Bearer secret")).when(processing).process(argThat(ref->ref.runId().equals(ID)),any());
  var context=mock(Context.class);when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
  var failure=assertThrows(IllegalStateException.class,()->handler.handleRequest(Map.of("Records",List.of(record(ID),Map.of(),record(other))),context));
  assertEquals("Worker invocation failed; retry required",failure.getMessage());assertNull(failure.getCause());
  verify(processing).process(argThat(ref->ref.runId().equals(other)),any());verify(processing,times(2)).process(any(),any());
 }
}
