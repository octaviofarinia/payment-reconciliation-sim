package org.octavio.paymentreconciliationsim.run;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.octavio.paymentreconciliationsim.storage.SettlementStorage;
import org.octavio.paymentreconciliationsim.businessdate.BusinessDateService;
import org.octavio.paymentreconciliationsim.model.Purchase;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class RunServiceTest {
 static final UUID ID=UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
 static final LocalDate DATE=LocalDate.of(2026,10,1);static final Instant NOW=Instant.parse("2026-10-02T02:30:00Z");
 static final String HASH="a".repeat(64),KEY="settlements/2026-10-01/"+ID+".csv";
 static final ObjectIdentity OBJECT=new ObjectIdentity("bucket",KEY,"version1",HASH);
 final RunRepository repository=mock(RunRepository.class);
 final BusinessDateService dates=mock(BusinessDateService.class);
 final SettlementStorage storage=mock(SettlementStorage.class);
 final RunService service=new RunService(repository,dates,Clock.fixed(Instant.parse("2026-10-02T15:00:00.123456Z"),ZoneOffset.UTC),new ReportValidator(),new ReportCanonicalizer(),storage);
 @BeforeEach void signing(){
  when(storage.upload(anyString(),anyString(),anyLong())).thenReturn(new SettlementStorage.UploadInstructions("http://local/upload",Map.of("x-amz-checksum-sha256","checksum"),NOW.plusSeconds(600)));
 }
 @Test void persistedKeyIsSignedAfterInsertionAndIncompleteReplaysGetInstructions(){
  when(repository.insert(any())).thenAnswer(call->call.getArgument(0));
  var created=service.register(new RegisterRun(DATE,HASH,100));
  var order=inOrder(repository,storage);
  order.verify(repository).insert(any());
  order.verify(storage).upload(created.objectKey(),HASH,100);
  assertEquals("http://local/upload",created.uploadInstructions().url());
  doThrow(new DuplicateKeyException("existing")).when(repository).insert(any());
  for(var state:List.of(Status.AWAITING_UPLOAD,Status.PROCESSING,Status.FAILED)){
   when(repository.logical("2026-10-01",HASH)).thenReturn(run(state,OBJECT,null));
   var replay=service.register(new RegisterRun(DATE,HASH,100));
   assertFalse(replay.created());assertEquals(KEY,replay.objectKey());assertNotNull(replay.uploadInstructions());
   assertEquals(state,replay.status());
  }
  verify(storage,times(3)).upload(KEY,HASH,100);
 }
 @Test void completedRegistrationDoesNotRequestANewPresignedUrl(){
  doThrow(new DuplicateKeyException("existing")).when(repository).insert(any());
  when(repository.logical("2026-10-01",HASH)).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  assertNull(service.register(new RegisterRun(DATE,HASH,100)).uploadInstructions());
  verify(storage,never()).upload(anyString(),anyString(),anyLong());
 }
 static final Summary EMPTY=new Summary(0,0,0,0,Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,0,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0));
 static ReportSubmission report(ObjectIdentity object){return new ReportSubmission(new InputIdentity("SIMULATED","2026-10-01",HASH,"v1",object),EMPTY,List.of());}
 static ReconciliationRun run(Status status,ObjectIdentity object,ReportSubmission report){return new ReconciliationRun(ID.toString(),"SIMULATED","2026-10-01",HASH,"v1",100,KEY,status,object,null,report,NOW,NOW);}
 static void status(int code,Runnable operation){assertEquals(code,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value());}
 @Test void persistsNewIdentityBeforeReturningAwaitingRegistrationAndReplaysDuplicate(){
  when(repository.insert(any())).thenAnswer(call->{ReconciliationRun registered=call.getArgument(0);assertEquals(Instant.parse("2026-10-02T15:00:00.123Z"),registered.createdAt());assertEquals(registered.createdAt(),registered.updatedAt());return registered;});
  var created=service.register(new RegisterRun(DATE,HASH,100));assertTrue(created.created());assertEquals(Status.AWAITING_UPLOAD,created.status());assertNotNull(created.uploadInstructions());
  assertEquals("settlements/2026-10-01/"+created.runId()+".csv",created.objectKey());
  doThrow(new DuplicateKeyException("existing")).when(repository).insert(any());when(repository.logical("2026-10-01",HASH)).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  var replay=service.register(new RegisterRun(DATE,HASH,100));assertFalse(replay.created());assertEquals(ID,replay.runId());assertEquals(Status.COMPLETED,replay.status());assertNull(replay.uploadInstructions());
  status(409,()->service.register(new RegisterRun(DATE,HASH,101)));
 }
 @Test void rejectsInvalidRegistrationAndUsesArgentinaBusinessDate(){
  for(var request:List.of(new RegisterRun(null,HASH,1),new RegisterRun(DATE,null,1),new RegisterRun(DATE,"A".repeat(64),1),new RegisterRun(DATE,"a",1),new RegisterRun(DATE,HASH,0),new RegisterRun(DATE,HASH,-1),new RegisterRun(DATE,HASH,2097153)))status(400,()->service.register(request));
  status(400,()->service.register(null));
  status(409,()->service.register(new RegisterRun(LocalDate.of(2026,10,2),HASH,100)));
  status(409,()->service.register(new RegisterRun(LocalDate.of(2026,10,3),HASH,100)));
  var argentine=new RunService(repository,dates,Clock.fixed(NOW,ZoneOffset.UTC),new ReportValidator(),new ReportCanonicalizer(),storage);
  status(409,()->argentine.register(new RegisterRun(DATE,HASH,100)));
  when(repository.insert(any())).thenAnswer(call->call.getArgument(0));
  assertTrue(service.register(new RegisterRun(DATE,HASH,2097152)).created());
 }
 @Test void returnsAllClosedPurchasesAndMetadataAndListing(){
  var awaiting=run(Status.AWAITING_UPLOAD,null,null);when(repository.find(ID.toString())).thenReturn(awaiting);
  when(dates.closedInputs(DATE)).thenReturn(List.of(new Purchase("Z","M",DATE.toString(),Long.MAX_VALUE,"ARS",NOW),new Purchase("A","N",DATE.toString(),1,"ARS",NOW)));
  assertEquals(List.of(new PurchaseInput("A","N",1),new PurchaseInput("Z","M",Long.MAX_VALUE)),service.input(ID).purchases());
  var metadata=service.metadata(ID);assertEquals(ID,metadata.runId());assertEquals("SIMULATED",metadata.source());assertNull(metadata.summary());assertNull(metadata.objectIdentity());
  when(repository.list(DATE.toString())).thenReturn(List.of(awaiting));assertEquals(List.of(metadata),service.list(DATE));
  var completed=run(Status.COMPLETED,OBJECT,report(OBJECT));when(repository.find(ID.toString())).thenReturn(completed);
  assertEquals(EMPTY,service.metadata(ID).summary());assertEquals(OBJECT,service.input(ID).objectIdentity());
 }
 @Test void firstObjectBindingIsAtomicAndConflictingVersionIsRejected(){
  when(repository.find(ID.toString())).thenReturn(run(Status.AWAITING_UPLOAD,null,null));
  when(repository.processing(eq(ID.toString()),eq(OBJECT),any())).thenReturn(run(Status.PROCESSING,OBJECT,null));
  assertDoesNotThrow(()->service.markProcessing(ID,OBJECT));
  when(repository.processing(eq(ID.toString()),eq(OBJECT),any())).thenReturn(null);
  when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  assertDoesNotThrow(()->service.markProcessing(ID,OBJECT));
  var other=new ObjectIdentity("bucket",KEY,"version2",HASH);
  status(409,()->service.markProcessing(ID,other));
  when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,null,null));status(409,()->service.markProcessing(ID,OBJECT));
 }

 @Test void completedProcessingReplayDoesNotRebuildOrResizePublishedDocument(){
  when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Processing candidate would be larger")).when(repository).checkSize(any());
  assertDoesNotThrow(()->service.markProcessing(ID,OBJECT));
  status(409,()->service.markProcessing(ID,new ObjectIdentity("bucket",KEY,"different",HASH)));
 }
 @Test void invalidObjectIdentitiesNeverReachBinding(){
  when(repository.find(ID.toString())).thenReturn(run(Status.AWAITING_UPLOAD,null,null));
  when(repository.processing(eq(ID.toString()),any(),any())).thenReturn(run(Status.PROCESSING,OBJECT,null));
  var objects=Arrays.asList(null,new ObjectIdentity(null,KEY,"v",HASH),new ObjectIdentity("",KEY,"v",HASH),new ObjectIdentity("bucket","wrong","v",HASH),new ObjectIdentity("bucket",KEY,null,HASH),new ObjectIdentity("bucket",KEY,"",HASH),new ObjectIdentity("bucket",KEY,"v","b".repeat(64)));
  for(var object:objects)status(409,()->service.markProcessing(ID,object));
 }
 @Test void publicationReplayCannotDowngradeCompletedRun(){
  var canonical=report(OBJECT);when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,OBJECT,null));
  when(repository.publish(eq(ID.toString()),eq(OBJECT),eq(canonical),any())).thenReturn(run(Status.COMPLETED,OBJECT,canonical));
  assertTrue(service.publish(ID,canonical).published());
  when(repository.publish(eq(ID.toString()),eq(OBJECT),eq(canonical),any())).thenReturn(null);
  when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,canonical));
  var replay=service.publish(ID,canonical);assertFalse(replay.published());assertEquals(Status.COMPLETED,replay.run().status());assertEquals(EMPTY,replay.run().summary());
  service.fail(ID,new FailureSubmission("TRANSPORT",true,"attempt1"));
  assertEquals(Status.COMPLETED,service.metadata(ID).status());
 }
 @Test void publicationRequiresFullRegisteredAndBoundIdentity(){
  when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,OBJECT,null));
  status(409,()->service.publish(ID,null));status(409,()->service.publish(ID,new ReportSubmission(null,EMPTY,List.of())));
  for(var identity:List.of(new InputIdentity("OTHER",DATE.toString(),HASH,"v1",OBJECT),new InputIdentity("SIMULATED","2026-09-30",HASH,"v1",OBJECT),new InputIdentity("SIMULATED",DATE.toString(),"b".repeat(64),"v1",OBJECT),new InputIdentity("SIMULATED",DATE.toString(),HASH,"v2",OBJECT),new InputIdentity("SIMULATED",DATE.toString(),HASH,"v1",null))){
   status(409,()->service.publish(ID,new ReportSubmission(identity,EMPTY,List.of())));
  }
  when(repository.find(ID.toString())).thenReturn(run(Status.AWAITING_UPLOAD,null,null));
  status(409,()->service.publish(ID,report(OBJECT)));
  status(409,()->service.publish(ID,report(null)));
 }
 @Test void conflictsAndRacesReturn409AndUnknownRuns404(){
  when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,OBJECT,null));
  status(409,()->service.publish(ID,report(OBJECT)));
  when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,new ReportSubmission(report(OBJECT).inputIdentity(),new Summary(1,0,0,1,EMPTY.outcomeCounts()),List.of())));
  status(409,()->service.publish(ID,report(OBJECT)));
  when(repository.find(ID.toString())).thenReturn(null);
  status(404,()->service.metadata(ID));status(404,()->service.input(ID));status(404,()->service.publish(ID,report(OBJECT)));status(404,()->service.markProcessing(ID,OBJECT));status(404,()->service.fail(ID,new FailureSubmission("FAILED",true,"attempt")));
 }
 @Test void failureIsAtomicAndLateCompletedCallbackIsHarmless(){
  when(repository.fail(eq(ID.toString()),any(),any())).thenReturn(run(Status.FAILED,OBJECT,null));
  assertDoesNotThrow(()->service.fail(ID,new FailureSubmission("TRANSPORT",true,"attempt1")));
  when(repository.fail(eq(ID.toString()),any(),any())).thenReturn(null);when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  assertDoesNotThrow(()->service.fail(ID,new FailureSubmission("TRANSPORT",false,"attempt2")));
  for(var failure:Arrays.asList(null,new FailureSubmission(null,true,"a"),new FailureSubmission("",true,"a"),new FailureSubmission("bad",true,"a"),new FailureSubmission("X".repeat(65),true,"a"),new FailureSubmission("ERROR",true,null),new FailureSubmission("ERROR",true,""),new FailureSubmission("ERROR",true,"a".repeat(65))))status(400,()->service.fail(ID,failure));
 }
 @Test void pagingRejectsInvalidValuesCapsSizeAndPreservesHugePage(){
  when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  var expected=new ResultPage(Integer.MAX_VALUE,100,0,List.of());when(repository.results(ID.toString(),null,Integer.MAX_VALUE,100)).thenReturn(expected);
  assertEquals(expected,service.results(ID,null,Integer.MAX_VALUE,500));
  for(int size:List.of(0,-1))status(400,()->service.results(ID,null,0,size));status(400,()->service.results(ID,null,-1,1));
  when(repository.find(ID.toString())).thenReturn(run(Status.AWAITING_UPLOAD,null,null));status(409,()->service.results(ID,null,0,50));
 }

 @Test void publicationCannotBypassReportValidationOrSerializedSizeChecks(){
  when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,OBJECT,null));
  when(repository.publish(eq(ID.toString()),eq(OBJECT),any(),any())).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  var invalid=new ReportSubmission(report(OBJECT).inputIdentity(),new Summary(1,0,0,0,EMPTY.outcomeCounts()),List.of());
  status(400,()->service.publish(ID,invalid));
  doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)).when(repository).checkSize(any());
  status(413,()->service.publish(ID,report(OBJECT)));
  status(413,()->service.markProcessing(ID,OBJECT));
 }
 @Test void supportsMinimumBytesMaximumPagingAndMaximumAttemptId(){
  when(repository.insert(any())).thenAnswer(call->call.getArgument(0));
  assertTrue(service.register(new RegisterRun(DATE,HASH,1)).created());
  when(repository.find(ID.toString())).thenReturn(run(Status.COMPLETED,OBJECT,report(OBJECT)));
  var page=new ResultPage(0,1,0,List.of());when(repository.results(ID.toString(),Outcome.MATCHED,0,1)).thenReturn(page);assertEquals(page,service.results(ID,Outcome.MATCHED,0,1));
  when(repository.fail(eq(ID.toString()),any(),any())).thenReturn(run(Status.FAILED,OBJECT,null));
  assertDoesNotThrow(()->service.fail(ID,new FailureSubmission("ERROR",true,"a".repeat(64))));
 }

 @Test void processingCallbackHandlesPublicationWinningBetweenReadAndAtomicBinding(){
  when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,OBJECT,null),run(Status.COMPLETED,OBJECT,report(OBJECT)));
  assertDoesNotThrow(()->service.markProcessing(ID,OBJECT));
  var other=new ObjectIdentity("bucket",KEY,"version2",HASH);
  when(repository.find(ID.toString())).thenReturn(run(Status.PROCESSING,OBJECT,null),run(Status.COMPLETED,OBJECT,report(OBJECT)));
  status(409,()->service.markProcessing(ID,other));
 }

 @Test void incompleteStatusIsStaleOnlyStrictlyBeyondTwentyMinutes() {
  var json=tools.jackson.databind.json.JsonMapper.builder().build();
  for(var status:Status.values()) {
   var stored=run(status,OBJECT,status==Status.COMPLETED?report(OBJECT):null);
   when(repository.find(ID.toString())).thenReturn(stored);
   for(long elapsed:List.of(1199999L,1200000L,1200001L)) {
    var timed=new RunService(repository,dates,Clock.fixed(NOW.plusMillis(elapsed),ZoneOffset.UTC),new ReportValidator(),new ReportCanonicalizer(),storage);
    var view=json.valueToTree(timed.metadata(ID));
    assertEquals(status!=Status.COMPLETED && elapsed>1200000L,view.path("recoveryNeeded").asBoolean());
   }
  }
 }
}
