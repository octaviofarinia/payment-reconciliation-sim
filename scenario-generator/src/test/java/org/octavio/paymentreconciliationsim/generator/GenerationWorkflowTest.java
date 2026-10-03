package org.octavio.paymentreconciliationsim.generator;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
class GenerationWorkflowTest {
 SimulatorClient client=mock(SimulatorClient.class);
 Scenario scenario=new ScenarioFactory().generate(42,LocalDate.of(2026,10,1),ScenarioKind.CANONICAL);
 ExpectedReport expected=new ScenarioFactory().expected(scenario,ScenarioKind.CANONICAL);
 MutableClock clock=new MutableClock();Registration registration=new Registration("run-123","key","PENDING_UPLOAD",new UploadInstructions("http://s3",Map.of()),true);
 @BeforeEach void setup(){when(client.register(any(),any(),anyInt())).thenReturn(registration);when(client.results("run-123")).thenReturn(expected.results());}
 GenerationWorkflow workflow(GenerationWorkflow.Delay delay){return new GenerationWorkflow(client,clock,delay,Duration.ofMinutes(5));}
 @Test void publicWorkflowPollsPendingAndProcessingThenVerifies(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","PENDING_UPLOAD",null,null),new RunMetadata("run-123","PROCESSING",null,null),new RunMetadata("run-123","COMPLETED",null,expected.summary()));
  var result=assertDoesNotThrow(()->workflow(clock::advance).run(scenario,expected));assertEquals(new VerifiedRun("run-123",expected),result);assertEquals(Instant.EPOCH.plusSeconds(2),clock.instant());
  var order=inOrder(client);order.verify(client).createDate(scenario.businessDate());for(var p:scenario.purchases())order.verify(client).createPurchase(p);order.verify(client).closeDate(scenario.businessDate());order.verify(client).register(eq(scenario.businessDate()),matches("[a-f0-9]{64}"),eq(211));order.verify(client).upload(eq(registration.uploadInstructions()),any());
 }
 @Test void failedRunIncludesRecoveryContext(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","FAILED",new Failure("INVALID_UTF8",false),null));
  var error=assertThrows(IllegalStateException.class,()->workflow(clock::advance).run(scenario,expected));assertTrue(error.getMessage().contains("runId=run-123"));assertTrue(error.getMessage().contains("INVALID_UTF8"));
 }
 @Test void deadlineStopsAtExactlyFiveMinutes(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","PENDING_UPLOAD",null,null));
  var error=assertThrows(IllegalStateException.class,()->workflow(clock::advance).run(scenario,expected));assertTrue(error.getMessage().contains("runId=run-123"));assertTrue(error.getMessage().contains("deadline"));assertEquals(Instant.EPOCH.plusSeconds(300),clock.instant());verify(client,times(300)).metadata(eq("run-123"),any(Duration.class));
 }
 @Test void interruptPreservesFlagAndRunId(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","PROCESSING",null,null));
  try{var error=assertThrows(IllegalStateException.class,()->workflow(d->{throw new InterruptedException();}).run(scenario,expected));assertTrue(error.getMessage().contains("runId=run-123"));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
 }
 @Test void mismatchAndUploadFailureIncludeRunId(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","COMPLETED",null,expected.summary()));when(client.results("run-123")).thenReturn(List.of());
  assertTrue(assertThrows(IllegalStateException.class,()->workflow(clock::advance).run(scenario,expected)).getMessage().contains("runId=run-123"));
  doThrow(new IllegalStateException("Upload HTTP 403")).when(client).upload(any(),any());
  assertTrue(assertThrows(IllegalStateException.class,()->workflow(clock::advance).run(scenario,expected)).getMessage().contains("runId=run-123"));
 }
 @Test void conflictBeforeRegistrationFailsWithoutInventingRunId(){
  doThrow(new IllegalStateException("API HTTP 409")).when(client).createDate(any());
  assertEquals("API HTTP 409",assertThrows(IllegalStateException.class,()->workflow(clock::advance).run(scenario,expected)).getMessage());verify(client,never()).register(any(),any(),anyInt());
 }
 static class MutableClock extends Clock {Instant value=Instant.EPOCH;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}void advance(Duration d){value=value.plus(d);}}

 @Test void replayCompletedDoesNotUpload(){
  when(client.register(any(),any(),anyInt())).thenReturn(new Registration("run-123","key","COMPLETED",null,false));
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","COMPLETED",null,expected.summary()));
  assertEquals("run-123",workflow(clock::advance).run(scenario,expected).runId());verify(client,never()).upload(any(),any());
 }
 @Test void remainingFractionOfSecondIsBoundedAndNoDelayAfterSlowResponse(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenReturn(new RunMetadata("run-123","PROCESSING",null,null));
  var shortWorkflow=new GenerationWorkflow(client,clock,clock::advance,Duration.ofMillis(1500));
  assertThrows(IllegalStateException.class,()->shortWorkflow.run(scenario,expected));assertEquals(Instant.EPOCH.plusMillis(1500),clock.instant());
  for(int extra:List.of(0,1)){
   var ticking=new Clock(){int calls;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return ++calls>=5?Instant.EPOCH.plusSeconds(300+extra):Instant.EPOCH;}};
   var expiring=new GenerationWorkflow(client,ticking,d->fail("No delay after deadline"),Duration.ofMinutes(5));
   assertThrows(IllegalStateException.class,()->expiring.run(scenario,expected));
  }
 }

 @Test void completionArrivingAfterDeadlineStillFailsWithRecoveryContext(){
  when(client.metadata(eq("run-123"),any(Duration.class))).thenAnswer(i->{clock.advance(Duration.ofMinutes(5));return new RunMetadata("run-123","COMPLETED",null,expected.summary());});
  var error=assertThrows(IllegalStateException.class,()->workflow(clock::advance).run(scenario,expected));
  assertTrue(error.getMessage().contains("deadline"));assertTrue(error.getMessage().contains("runId=run-123"));verify(client,never()).results(any());
 }
}
