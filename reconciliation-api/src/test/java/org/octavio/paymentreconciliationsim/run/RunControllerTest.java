package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class RunControllerTest {
 @Test void registrationUsesCreatedOrReplayStatusAndExposesStableResponse(){
  var service=mock(RunService.class);var controller=new RunController(service,mock(RecoveryService.class));
  var created=new RegistrationResult(RunServiceTest.ID,RunServiceTest.KEY,Status.AWAITING_UPLOAD,new org.octavio.paymentreconciliationsim.storage.SettlementStorage.UploadInstructions("https://upload.example",Map.of("x-amz-checksum-sha256","YWJj"),RunServiceTest.NOW),true);
  var replay=new RegistrationResult(RunServiceTest.ID,RunServiceTest.KEY,Status.COMPLETED,null,false);
  when(service.register(new RegisterRun(RunServiceTest.DATE,RunServiceTest.HASH,100))).thenReturn(created,replay);
  var body=JsonMapper.builder().build().readTree("{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100}");
  var first=controller.register(body);assertEquals(201,first.getStatusCode().value());assertEquals(created,first.getBody());
  var second=controller.register(body);assertEquals(200,second.getStatusCode().value());assertEquals(replay,second.getBody());
 }
 @Test void publicAndWorkerContractsUseRunIdInputAndPagingValues(){
  var service=mock(RunService.class);var controller=new RunController(service,mock(RecoveryService.class));var worker=new WorkerController(service);
  var metadata=new RunMetadata(RunServiceTest.ID,"SIMULATED","2026-10-01",RunServiceTest.HASH,"v1",100,RunServiceTest.KEY,Status.COMPLETED,RunServiceTest.OBJECT,null,RunServiceTest.EMPTY,RunServiceTest.NOW,RunServiceTest.NOW,false);
  var input=new RunInput(RunServiceTest.ID,"SIMULATED","2026-10-01",RunServiceTest.HASH,"v1",RunServiceTest.OBJECT,List.of());
  var page=new ResultPage(1,10,0,List.of());
  when(service.metadata(RunServiceTest.ID)).thenReturn(metadata);when(service.input(RunServiceTest.ID)).thenReturn(input);
  when(service.list(RunServiceTest.DATE)).thenReturn(List.of(metadata));when(service.results(RunServiceTest.ID,Outcome.MATCHED,1,10)).thenReturn(page);
  assertEquals(metadata,controller.metadata(RunServiceTest.ID));assertEquals(metadata,worker.metadata(RunServiceTest.ID));assertEquals(input,worker.input(RunServiceTest.ID));
  assertEquals(List.of(metadata),controller.list("2026-10-01"));assertEquals(page,controller.results(RunServiceTest.ID,Outcome.MATCHED,1,10));
 }
 @Test void workerCallbacksDecodeCompleteBodiesAtTheBoundary(){
  var service=mock(RunService.class);var controller=new WorkerController(service);var json=JsonMapper.builder().build();
  var expectedObject=new ObjectIdentity("bucket","key","v1",RunServiceTest.HASH);
  doThrow(new IllegalStateException("bound")).when(service).markProcessing(RunServiceTest.ID,expectedObject);
  assertEquals("bound",assertThrows(IllegalStateException.class,()->controller.processing(RunServiceTest.ID,json.readTree(RunRequestDecoderTest.OBJECT))).getMessage());
  doNothing().when(service).markProcessing(RunServiceTest.ID,expectedObject);assertDoesNotThrow(()->controller.processing(RunServiceTest.ID,json.readTree(RunRequestDecoderTest.OBJECT)));
  var report=RunRequestDecoder.report(json.readTree(RunRequestDecoderTest.REPORT));var published=new PublicationResult(true,null);
  when(service.publish(RunServiceTest.ID,report)).thenReturn(published);
  assertEquals(published,controller.results(RunServiceTest.ID,json.readTree(RunRequestDecoderTest.REPORT)));
  doThrow(new IllegalStateException("failed")).when(service).fail(RunServiceTest.ID,new FailureSubmission("ERROR",true,"attempt"));
  assertEquals("failed",assertThrows(IllegalStateException.class,()->controller.failure(RunServiceTest.ID,json.readTree("{\"code\":\"ERROR\",\"retriable\":true,\"attemptId\":\"attempt\"}"))).getMessage());
  doNothing().when(service).fail(RunServiceTest.ID,new FailureSubmission("ERROR",true,"attempt"));assertDoesNotThrow(()->controller.failure(RunServiceTest.ID,json.readTree("{\"code\":\"ERROR\",\"retriable\":true,\"attemptId\":\"attempt\"}")));
 }

 @Test void recoveryReturnsTopLevelMetadataWith202AcceptanceOr200Completed() {
  var recovery=mock(RecoveryService.class);var controller=new RunController(mock(RunService.class),recovery);
  var metadata=new RunMetadata(RunServiceTest.ID,"SIMULATED","2026-10-01",RunServiceTest.HASH,"v1",100,
    RunServiceTest.KEY,Status.PROCESSING,RunServiceTest.OBJECT,null,null,RunServiceTest.NOW,RunServiceTest.NOW,false);
  when(recovery.reprocess(RunServiceTest.ID)).thenReturn(new ReprocessResult(true,metadata),new ReprocessResult(false,metadata));
  var first=controller.reprocess(RunServiceTest.ID);assertEquals(202,first.getStatusCode().value());assertEquals(metadata,first.getBody());
  var second=controller.reprocess(RunServiceTest.ID);assertEquals(200,second.getStatusCode().value());assertEquals(metadata,second.getBody());
 }

 @Test void recoveryConflictsExposeOnlyStableProblemDetailsAndCodes() {
  var controller=new RunController(mock(RunService.class),mock(RecoveryService.class));
  var conflict=new RecoveryService.RecoveryConflict("UPLOAD_NEEDED","Upload needed: no verified retained settlement input");
  var response=controller.recoveryConflict(conflict);
  assertEquals(409,response.getStatusCode().value());assertSame(conflict.getBody(),response.getBody());
  assertEquals(409,response.getBody().getStatus());assertEquals("Conflict",response.getBody().getTitle());
  assertEquals(java.net.URI.create("about:blank"),response.getBody().getType());
  assertEquals("Upload needed: no verified retained settlement input",response.getBody().getDetail());
  assertEquals(Map.of("code","UPLOAD_NEEDED"),response.getBody().getProperties());
 }
}
