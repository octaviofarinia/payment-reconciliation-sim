package org.octavio.paymentreconciliationsim.acceptance;
import java.net.http.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import org.octavio.paymentreconciliationsim.worker.*;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient;
import org.octavio.paymentreconciliationsim.worker.storage.S3SettlementReader;
import org.octavio.paymentreconciliationsim.worker.csv.SettlementCsvParser;
import org.octavio.paymentreconciliationsim.worker.domain.ReconciliationComparator;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryIT extends WorkerRetryIT {
 @Test void retainedUnboundUploadIsRecoveredThroughTheRealAsynchronousHandler() throws Exception {
  env.resetScenario();
  assertEquals(201,request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}").statusCode());
  assertEquals(200,request("POST","/api/v1/business-dates/2026-10-01/close","{}").statusCode());
  byte[] bytes=fixture("header-only.csv").getBytes(StandardCharsets.UTF_8);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  var registration=JSON.readTree(request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+hash+"\",\"byteLength\":"+bytes.length+"}").body());
  String id=registration.get("runId").stringValue(),key=registration.get("objectKey").stringValue();
  String version=env.s3().store(LocalEnvironment.BUCKET,key,bytes);
  assertEquals(202,request("POST","/api/v1/reconciliation-runs/"+id+"/reprocess","{}").statusCode());
  env.awaitWorker();
  var completed=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body());
  assertEquals("COMPLETED",completed.get("status").stringValue());
  assertFalse(completed.get("recoveryNeeded").booleanValue());
  assertEquals(version,completed.get("objectIdentity").get("versionId").stringValue());
  assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
  assertEquals(200,request("POST","/api/v1/reconciliation-runs/"+id+"/reprocess","{}").statusCode());
  assertEquals(completed,JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body()));
 }

 record Registered(String id,String key,String version) {
  Map<String,Object> event() { return Map.of("runId",id,"bucket",LocalEnvironment.BUCKET,"key",key,"versionId",version); }
  String path() { return "/api/v1/reconciliation-runs/"+id; }
 }
 Registered prepare(boolean retain) throws Exception {
  env.resetScenario();
  request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}");
  request("POST","/api/v1/business-dates/2026-10-01/close","{}");
  byte[] bytes=fixture("header-only.csv").getBytes(StandardCharsets.UTF_8);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  var registration=JSON.readTree(request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+hash+"\",\"byteLength\":"+bytes.length+"}").body());
  String id=registration.get("runId").stringValue(),key=registration.get("objectKey").stringValue();
  String version=retain?env.s3().store(LocalEnvironment.BUCKET,key,bytes):"missing";
  return new Registered(id,key,version);
 }
 @Test void transientS3FailureIsPersistedThenManualRecoveryKeepsOriginalVersion() throws Exception {
  var run=prepare(true);
  env.s3().failReads(true);
  assertThrows(IllegalStateException.class,()->env.invokeWorker(run.event()));
  var failed=JSON.readTree(request("GET",run.path(),null).body());
  assertEquals("FAILED",failed.get("status").stringValue());
  assertEquals("S3_FAILURE",failed.get("error").get("code").stringValue());
  assertTrue(failed.get("error").get("retriable").booleanValue());
  assertFalse(failed.get("error").get("attemptId").stringValue().isEmpty());
  env.s3().failReads(false);
  assertEquals(202,request("POST",run.path()+"/reprocess","{}").statusCode());
  env.awaitWorker();
  assertEquals("COMPLETED",JSON.readTree(request("GET",run.path(),null).body()).get("status").stringValue());
  assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
 }
 @Test void missingBoundOriginalNeverSubstitutesAnotherVersionAndCompletedReplayNeedsNoS3() throws Exception {
  var run=prepare(true);
  String hash=JSON.readTree(request("GET",run.path(),null).body()).get("sha256").stringValue();
  var identity=Map.of("bucket",LocalEnvironment.BUCKET,"key",run.key(),"versionId",run.version(),"sha256",hash);
  try(var http=HttpClient.newHttpClient()) {
   var response=http.send(HttpRequest.newBuilder(env.apiBaseUri().resolve("/internal/v1/reconciliation-runs/"+run.id()+"/processing"))
     .header("Content-Type","application/json").header("Authorization","Bearer "+LocalEnvironment.WORKER_TOKEN)
     .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(identity))).build(),
     HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());
  }
  env.s3().remove(LocalEnvironment.BUCKET,run.key(),run.version());
  env.s3().store(LocalEnvironment.BUCKET,run.key(),fixture("header-only.csv").getBytes(StandardCharsets.UTF_8));
  var conflict=request("POST",run.path()+"/reprocess","{}");
  assertEquals(409,conflict.statusCode());
  assertEquals("Original bound settlement version is unavailable; replacement is forbidden",JSON.readTree(conflict.body()).path("detail").asText());
  assertEquals("ORIGINAL_UNAVAILABLE",JSON.readTree(conflict.body()).path("code").asText());
  assertEquals(run.path()+"/reprocess",JSON.readTree(conflict.body()).path("instance").asText());
  assertEquals(run.version(),JSON.readTree(request("GET",run.path(),null).body()).get("objectIdentity").get("versionId").stringValue());
  var completed=prepare(true);env.invokeWorker(completed.event());
  String before=request("GET",completed.path(),null).body();
  env.s3().failReads(true);
  assertEquals(200,request("POST",completed.path()+"/reprocess","{}").statusCode());
  assertEquals(JSON.readTree(before),JSON.readTree(request("GET",completed.path(),null).body()));
 }
 @Test void missingUnboundUploadIs409AndStaleFlagDoesNotClaimExhaustedRetries() throws Exception {
  var run=prepare(false);
  var conflict=request("POST",run.path()+"/reprocess","{}");
  assertEquals(409,conflict.statusCode());
  assertEquals("Upload needed: no verified retained settlement input",JSON.readTree(conflict.body()).path("detail").asText());
  assertEquals("UPLOAD_NEEDED",JSON.readTree(conflict.body()).path("code").asText());
  assertEquals("about:blank",JSON.readTree(conflict.body()).path("type").asText());
  assertTrue(conflict.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
  var collection=env.mongoDatabase().getCollection("reconciliation_runs");
  for(long elapsed:List.of(1200000L,1200001L)) {
   collection.updateOne(new org.bson.Document("_id",run.id()),new org.bson.Document("$set",
     new org.bson.Document("updatedAt",java.util.Date.from(env.clock().instant().minusMillis(elapsed)))));
   var metadata=JSON.readTree(request("GET",run.path(),null).body());
   assertEquals(elapsed>1200000L,metadata.get("recoveryNeeded").booleanValue());
   assertEquals("AWAITING_UPLOAD",metadata.get("status").stringValue());
   assertFalse(metadata.has("retriesExhausted"));
  }
 }
 @Test void failureCallbackOutagePreservesLastKnownStatusAndLaterRecoverySucceeds() throws Exception {
  var run=prepare(true);
  try(var proxy=new FaultProxy(env.apiBaseUri());
      var s3=S3Client.builder().region(Region.US_EAST_1)
        .endpointOverride(env.s3Endpoint()).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
          AwsBasicCredentials.create("acceptance","acceptance"))).build();
      var http=HttpClient.newHttpClient()) {
   proxy.unavailable=false;proxy.losePublication=false;proxy.failureUnavailable=true;
   var handler=new ReconciliationHandler(new ProcessingService(
     LocalEnvironment.BUCKET,
     new WorkerApiClient(proxy.uri(),"acceptance-worker-token",http),
     new S3SettlementReader(s3),
     new SettlementCsvParser(),
     new ReconciliationComparator()));
   env.s3().failReads(true);
   assertThrows(IllegalStateException.class,()->handler.handleRequest(run.event(),null));
   assertEquals("AWAITING_UPLOAD",JSON.readTree(request("GET",run.path(),null).body()).get("status").stringValue());
   env.s3().failReads(false);proxy.failureUnavailable=false;proxy.inputUnavailable=true;
   assertThrows(IllegalStateException.class,()->handler.handleRequest(run.event(),null));
   var failed=JSON.readTree(request("GET",run.path(),null).body());
   assertEquals("FAILED",failed.get("status").stringValue());
   assertEquals("API_FAILURE",failed.get("error").get("code").stringValue());
   assertTrue(failed.get("error").get("retriable").booleanValue());
   proxy.inputUnavailable=false;
   assertEquals(202,request("POST",run.path()+"/reprocess","{}").statusCode());
   env.awaitWorker();
   var recovered=JSON.readTree(request("GET",run.path(),null).body());
   assertEquals("COMPLETED",recovered.get("status").stringValue());
   assertFalse(recovered.get("recoveryNeeded").booleanValue());
   assertEquals(run.version(),recovered.get("objectIdentity").get("versionId").stringValue());
  }
 }
 @Test void lostCompletionResponseIsRecoverableWithoutDuplicateReport() throws Exception {
  var run=prepare(true);
  try(var proxy=new FaultProxy(env.apiBaseUri());
      var s3=S3Client.builder().region(Region.US_EAST_1)
        .endpointOverride(env.s3Endpoint()).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
          AwsBasicCredentials.create("acceptance","acceptance"))).build();
      var http=HttpClient.newHttpClient()) {
   proxy.unavailable=false;
   var handler=new ReconciliationHandler(new ProcessingService(
     LocalEnvironment.BUCKET,
     new WorkerApiClient(proxy.uri(),"acceptance-worker-token",http),
     new S3SettlementReader(s3),
     new SettlementCsvParser(),
     new ReconciliationComparator()));
   assertThrows(IllegalStateException.class,()->handler.handleRequest(run.event(),null));
   assertEquals(200,request("POST",run.path()+"/reprocess","{}").statusCode());
   var recoveredRun=JSON.readValue(request("GET",run.path(),null).body(),org.octavio.paymentreconciliationsim.run.RunContracts.RunMetadata.class);
   long storedReportCount=env.mongoDatabase().getCollection("reconciliation_runs").countDocuments(new org.bson.Document("report",new org.bson.Document("$ne",null)));
   assertEquals("COMPLETED",recoveredRun.status().name());
   assertEquals(1,storedReportCount);
   assertFalse(recoveredRun.recoveryNeeded());
   assertNull(handler.handleRequest(run.event(),null));
   assertEquals(2,proxy.publications);
   assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments(new org.bson.Document("report",new org.bson.Document("$ne",null))));
  }
 }
}
