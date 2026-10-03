package org.octavio.paymentreconciliationsim.worker;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.worker.event.*;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient.*;
import org.octavio.paymentreconciliationsim.worker.config.WorkerConfiguration;
import org.octavio.paymentreconciliationsim.worker.storage.S3SettlementReader;
import org.octavio.paymentreconciliationsim.worker.csv.SettlementCsvParser;
import org.octavio.paymentreconciliationsim.worker.domain.*;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.ResponseInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProcessingServiceTest {
 static final String ID="12345678-1234-1234-1234-123456789abc",KEY="settlements/2026-10-01/"+ID+".csv",HASH="a".repeat(64);
 final WorkerApiClient api=mock(WorkerApiClient.class);final S3SettlementReader storage=mock(S3SettlementReader.class);
 final ProcessingService service=new ProcessingService("bucket",api,storage,new SettlementCsvParser(),new ReconciliationComparator());
 final ObjectReference ref=new ObjectReference(ID,"bucket",KEY,"version");final AttemptContext attempt=new AttemptContext("attempt",Instant.parse("2026-10-03T00:00:00Z"));
 final ObjectIdentity identity=new ObjectIdentity("bucket",KEY,"version",HASH);
 final String csv="business_date,transaction_reference,amount_centavos,currency\n2026-10-01,A,10,ARS\n";
 Metadata metadata(String id,String key,ObjectIdentity object){return new Metadata(id,"SIMULATED","2026-10-01",HASH,"v1",csv.length(),key,"PROCESSING",object);}
 RunInput input(String source,String date,String hash,String rules,ObjectIdentity object){return new RunInput(ID,source,date,hash,rules,object,List.of(new ReconciliationModel.PurchaseInput("A","M",10)));}
 void setup()throws Exception{when(api.metadata(ID)).thenReturn(metadata(ID,KEY,null));when(storage.read(ref,csv.length(),HASH)).thenReturn(csv.getBytes());when(api.input(ID)).thenReturn(input("SIMULATED","2026-10-01",HASH,"v1",identity));}
 @Test void orchestratesCompleteBoundInputAndPublishesWholeComparison()throws Exception{
  setup();assertDoesNotThrow(()->service.process(ref,attempt));
  var order=inOrder(api,storage);order.verify(api).metadata(ID);order.verify(storage).read(ref,csv.length(),HASH);order.verify(api).processing(ID,identity);order.verify(api).input(ID);
  var cap=org.mockito.ArgumentCaptor.forClass(ReportSubmission.class);order.verify(api).publish(eq(ID),cap.capture());
  assertEquals(new InputIdentity("SIMULATED","2026-10-01",HASH,"v1",identity),cap.getValue().inputIdentity());assertEquals(1,cap.getValue().summary().totalResultCount());assertEquals(ReconciliationModel.Outcome.MATCHED,cap.getValue().results().getFirst().outcome());
  assertEquals(Instant.parse("2026-10-03T00:00:00Z"),attempt.startedAt());verify(api,never()).failure(anyString(),any());
 }
 @Test void sameBoundVersionReplaysButForeignIdentityNeverTouchesObject()throws Exception{
  setup();when(api.metadata(ID)).thenReturn(metadata(ID,KEY,identity));service.process(ref,attempt);verify(api).publish(eq(ID),any());
  for(var meta:List.of(metadata("other",KEY,null),metadata(ID,"other",null),metadata(ID,KEY,new ObjectIdentity("bucket",KEY,"other",HASH)))){
   reset(api,storage);when(api.metadata(ID)).thenReturn(meta);assertEquals("INPUT_IDENTITY_MISMATCH",assertThrows(InputRejected.class,()->service.process(ref,attempt)).code());verifyNoInteractions(storage);
  }
  reset(api,storage);when(api.metadata(ID)).thenReturn(metadata(ID,KEY,null));assertThrows(InputRejected.class,()->service.process(new ObjectReference(ID,"foreign",KEY,"version"),attempt));verifyNoInteractions(storage);
 }
 @Test void unregisteredObjectIsDeterministicallyRejectedButOutageRetries(){
  when(api.metadata(ID)).thenThrow(new ApiFailure(404));assertEquals("UNREGISTERED_OBJECT",assertThrows(InputRejected.class,()->service.process(ref,attempt)).code());
  doThrow(new ApiFailure(503)).when(api).metadata(ID);assertEquals(503,assertThrows(ApiFailure.class,()->service.process(ref,attempt)).status());verifyNoInteractions(storage);
 }
 @Test void invalidCsvAndDeterministicObjectErrorRecordNonretryableFailureAndNeverPublish()throws Exception{
  setup();when(storage.read(ref,csv.length(),HASH)).thenReturn("invalid\n".getBytes());service.process(ref,attempt);verify(api).failure(ID,new FailureSubmission("INVALID_HEADER",false,"attempt"));verify(api,never()).publish(anyString(),any());
  reset(api,storage);setup();when(storage.read(ref,csv.length(),HASH)).thenThrow(new InputRejected("OBJECT_CHECKSUM_MISMATCH"));service.process(ref,attempt);
  verify(api).failure(ID,new FailureSubmission("OBJECT_CHECKSUM_MISMATCH",false,"attempt"));verify(api,never()).processing(anyString(),any());verify(api,never()).publish(anyString(),any());
 }
 @Test void callbackAndTransportFailuresRemainInvocationFailures()throws Exception{
  setup();when(storage.read(ref,csv.length(),HASH)).thenThrow(new IOException("transport"));assertEquals("Worker object transport failure",assertThrows(IllegalStateException.class,()->service.process(ref,attempt)).getMessage());verify(api).failure(ID,new FailureSubmission("OBJECT_TRANSPORT_FAILURE",true,"attempt"));
  reset(api,storage);setup();doThrow(new ApiFailure(503)).when(api).publish(eq(ID),any());assertThrows(ApiFailure.class,()->service.process(ref,attempt));verify(api).failure(ID,new FailureSubmission("API_FAILURE",true,"attempt"));
  reset(api,storage);setup();when(storage.read(ref,csv.length(),HASH)).thenReturn("invalid\n".getBytes());doThrow(new ApiFailure(503)).when(api).failure(eq(ID),any());assertThrows(ApiFailure.class,()->service.process(ref,attempt));
 }
 @Test void changedCompleteInputIsNeverPublished()throws Exception{
  setup();
  for(var input:List.of(input("other","2026-10-01",HASH,"v1",identity),input("SIMULATED","2026-09-30",HASH,"v1",identity),input("SIMULATED","2026-10-01","b".repeat(64),"v1",identity),input("SIMULATED","2026-10-01",HASH,"v2",identity),input("SIMULATED","2026-10-01",HASH,"v1",null),new RunInput("other","SIMULATED","2026-10-01",HASH,"v1",identity,List.of()))){
   when(api.input(ID)).thenReturn(input);assertEquals("Worker API input identity changed",assertThrows(IllegalStateException.class,()->service.process(ref,attempt)).getMessage());
  }
  verify(api,never()).publish(anyString(),any());verify(api,never()).failure(anyString(),any());
 }
 @Test void cloudBootstrapUsesRegionAndPrivateConfigWithoutHostCredentialsInTests(){
  var s3=mock(S3Client.class);var builder=mock(S3ClientBuilder.class);when(builder.region(any())).thenReturn(builder);when(builder.build()).thenReturn(s3);
  when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream("{\"workerToken\":\"dummy\"}".getBytes())));
  var env=Map.of("AWS_REGION","us-east-1","RECONCILIATION_API_URI","http://localhost","RECONCILIATION_CONFIG_BUCKET","bucket","RECONCILIATION_CONFIG_KEY","runtime-config/worker.json");
  try(var factory=mockStatic(S3Client.class)){
   factory.when(S3Client::builder).thenReturn(builder);assertNotNull(ProcessingService.cloud(env));verify(builder).region(software.amazon.awssdk.regions.Region.US_EAST_1);
  }
  assertThrows(IllegalArgumentException.class,()->ProcessingService.cloud(Map.of()));
 }

 @Test void diagnosticsCorrelateSuccessfulAndFailedAttemptsWithoutRawTransportOrSecrets() throws Exception {
  setup();
  var bytes=new ByteArrayOutputStream();
  var previous=System.out;
  try(var output=new PrintStream(bytes)) {
   System.setOut(output);
   service.process(ref,attempt);
   doThrow(new ApiFailure(503)).when(api).publish(eq(ID),any());
   assertThrows(ApiFailure.class,()->service.process(ref,attempt));
  } finally { System.setOut(previous); }
  var lines=bytes.toString().lines().toList();
  assertEquals(2,lines.size());
  var json=tools.jackson.databind.json.JsonMapper.builder().build();
  for(var line:lines) {
   var log=json.readTree(line);
   assertEquals(ID,log.get("runId").stringValue());
   assertEquals("2026-10-01",log.get("businessDate").stringValue());
   assertEquals("version",log.get("versionId").stringValue());
   assertEquals("v1",log.get("rulesVersion").stringValue());
   assertEquals("attempt",log.get("attemptId").stringValue());
   assertTrue(log.get("durationMillis").longValue()>=0);
  }
  assertTrue(json.readTree(lines.getFirst()).get("errorCode").isNull());
  assertEquals("API_FAILURE",json.readTree(lines.getLast()).get("errorCode").stringValue());
 }

 @Test void transientSdkAndApiTransportFailuresRecordStableRetryableCodesEvenWhenCallbackIsLost() throws Exception {
  setup();when(storage.read(ref,csv.length(),HASH)).thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("raw secret"));
  assertEquals("Worker object transport failure",assertThrows(IllegalStateException.class,()->service.process(ref,attempt)).getMessage());
  verify(api).failure(ID,new FailureSubmission("S3_FAILURE",true,"attempt"));
  reset(api,storage);setup();
  doThrow(new WorkerTransportFailure("Worker API transport failure")).when(api).input(ID);
  doThrow(new ApiFailure(503)).when(api).failure(eq(ID),any());
  assertInstanceOf(WorkerTransportFailure.class,assertThrows(IllegalStateException.class,()->service.process(ref,attempt)));
  verify(api).failure(ID,new FailureSubmission("API_FAILURE",true,"attempt"));
 }
 @Test void diagnosticNamespaceIsNoninstantiableAndEscapesCorrelationFields() throws Exception {
  var constructor=WorkerDiagnostics.class.getDeclaredConstructor();constructor.setAccessible(true);assertNotNull(constructor.newInstance());
  var value=WorkerDiagnostics.message(null,null,new AttemptContext("attempt\\\"escaped",Instant.now().plusSeconds(10)),"INVALID_INVOCATION");
  var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(value);
  assertEquals("attempt\\\"escaped",json.get("attemptId").stringValue());assertEquals(0,json.get("durationMillis").longValue());
 }
}
