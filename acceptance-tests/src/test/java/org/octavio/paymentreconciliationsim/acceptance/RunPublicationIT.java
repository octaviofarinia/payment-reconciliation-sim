package org.octavio.paymentreconciliationsim.acceptance;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class RunPublicationIT {
 static LocalEnvironment env; static final JsonMapper JSON=JsonMapper.builder().build();
 @BeforeAll static void start(){env=new LocalEnvironment();env.start();}
 @AfterAll static void stop(){if(env!=null)env.close();}
 @BeforeEach void reset(){env.resetScenario();}
 static HttpResponse<String> request(String method,String path,String body)throws Exception{
  try(var client=HttpClient.newHttpClient()){
   var builder=HttpRequest.newBuilder(env.apiBaseUri().resolve(path)).header("Content-Type","application/json").header("Authorization","Bearer "+(path.startsWith("/internal/")?LocalEnvironment.WORKER_TOKEN:LocalEnvironment.DEMO_TOKEN));
   builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
   return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  }
 }
 static String fixture(String name)throws Exception{try(var stream=RunPublicationIT.class.getResourceAsStream("/fixtures/"+name)){assertNotNull(stream);return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
 static String prepare()throws Exception{
  assertEquals(201,request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}").statusCode());
  for(var purchase:JSON.readTree(fixture("canonical-purchases.json")))assertEquals(201,request("POST","/api/v1/transactions",purchase.toString()).statusCode());
  assertEquals(200,request("POST","/api/v1/business-dates/2026-10-01/close","{}").statusCode());
  var registration=request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100}");
  assertEquals(201,registration.statusCode(),registration.body());
  return JSON.readTree(registration.body()).get("runId").stringValue();
 }
 static String object(String id){return "{\"bucket\":\"acceptance-settlements\",\"key\":\"settlements/2026-10-01/"+id+".csv\",\"versionId\":\"v1\",\"sha256\":\""+"a".repeat(64)+"\"}";}
 static String report(String id)throws Exception{return fixture("canonical-report.json").replace("OBJECT_KEY","settlements/2026-10-01/"+id+".csv");}
 @Test void canonicalReportPublishesExactSummaryAndExplicitNullableEvidence()throws Exception{
  var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;
  assertEquals(409,request("GET","/api/v1/reconciliation-runs/"+id+"/results",null).statusCode());
  var input=JSON.readTree(request("GET",base+"/input",null).body());assertEquals(4,input.get("purchases").size());
  assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
  assertEquals(200,request("PUT",base+"/results",report(id)).statusCode());
  var metadata=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body());
  assertEquals("COMPLETED",metadata.get("status").stringValue());
  var expected=JSON.readTree(fixture("canonical-expected.json"));
  assertEquals(expected.get("summary"),metadata.get("summary"));
  assertEquals(expected.get("results"),JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null).body()).get("results"));
  var aggregate=env.mongoDatabase().getCollection("reconciliation_runs").aggregate(java.util.List.of(new org.bson.Document("$unwind","$report.results"),new org.bson.Document("$group",new org.bson.Document("_id","$report.results.outcome").append("count",new org.bson.Document("$sum",1))))).into(new java.util.ArrayList<>());
  assertEquals(5,aggregate.size());for(var count:aggregate)assertEquals(1,count.getInteger("count"));
  var stored=env.mongoDatabase().getCollection("reconciliation_runs").find().first();
  var external=stored.get("report",org.bson.Document.class).getList("results",org.bson.Document.class).get(2);
  assertTrue(external.containsKey("internalAmountCentavos"));assertNull(external.get("internalAmountCentavos"));
  assertTrue(external.containsKey("merchantId"));assertNull(external.get("merchantId"));
 }

 @Test void publicationReplayCannotDowngradeCompletedRun()throws Exception{
  var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
  var original=report(id);
  // Ignore the first response to exercise the response-lost retry contract.
  request("PUT",base+"/results",original);
  var firstPublishedReport=request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null).body();
  var replay=request("PUT",base+"/results",original);assertEquals(200,replay.statusCode());assertFalse(JSON.readTree(replay.body()).get("published").booleanValue());
  var reordered=(tools.jackson.databind.node.ObjectNode)JSON.readTree(original);var results=(tools.jackson.databind.node.ArrayNode)reordered.get("results");
  var values=new java.util.ArrayList<tools.jackson.databind.JsonNode>();results.forEach(values::add);java.util.Collections.reverse(values);results.removeAll();values.forEach(results::add);
  var duplicate=(tools.jackson.databind.node.ArrayNode)results.get(3).get("settlementEvidence");var row=duplicate.remove(0);duplicate.add(row);
  assertEquals(200,request("PUT",base+"/results",reordered.toString()).statusCode());
  var conflictingReportResponse=request("PUT",base+"/results",original.replace("45000","45001"));
  assertEquals(409,conflictingReportResponse.statusCode());
  assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
  assertEquals(200,request("POST",base+"/failure","{\"code\":\"TRANSPORT\",\"retriable\":true,\"attemptId\":\"late\"}").statusCode());
  var finalStatus=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body()).get("status").stringValue();
  var replayedReport=request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null).body();
  assertEquals("COMPLETED",finalStatus);assertEquals(firstPublishedReport,replayedReport);
  assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
 }
 @Test void simultaneousIdenticalAndConflictingPublicationsHaveOneAtomicWinner()throws Exception{
  for(boolean conflicting:new boolean[]{false,true}){
   env.resetScenario();var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
   var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
   try{
    var a=pool.submit(()->{start.await();return request("PUT",base+"/results",report(id));});
    var b=pool.submit(()->{start.await();return request("PUT",base+"/results",conflicting?report(id).replace("45000","45001"):report(id));});start.countDown();
    var responses=java.util.List.of(a.get(),b.get());assertEquals(conflicting?java.util.List.of(200,409):java.util.List.of(200,200),responses.stream().map(HttpResponse::statusCode).sorted().toList());
    if(!conflicting)assertEquals(1,responses.stream().filter(v->JSON.readTree(v.body()).get("published").booleanValue()).count());
    var results=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null).body()).get("results");
    var original=JSON.readTree(fixture("canonical-expected.json")).get("results");
    var changed=JSON.readTree(fixture("canonical-expected.json").replace("45000","45001")).get("results");
    assertTrue(results.equals(original)||conflicting&&results.equals(changed));
    assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
   }finally{pool.shutdownNow();}
  }
 }
 @Test void simultaneousBindingsFixTheFirstVersionAndRegistrationHasUniqueLogicalIdentity()throws Exception{
  var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;
  var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   var a=pool.submit(()->{start.await();return request("PUT",base+"/processing",object(id));});
   var b=pool.submit(()->{start.await();return request("PUT",base+"/processing",object(id).replace("\"versionId\":\"v1\"","\"versionId\":\"v2\""));});start.countDown();
   assertEquals(java.util.List.of(200,409),java.util.List.of(a.get().statusCode(),b.get().statusCode()).stream().sorted().toList());
  }finally{pool.shutdownNow();}
  var identity=request("GET",base,null).body();assertTrue(identity.contains("\"versionId\":\"v1\"")||identity.contains("\"versionId\":\"v2\""));
  var registration="{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100}";
  assertEquals(id,JSON.readTree(request("POST","/api/v1/reconciliation-runs",registration).body()).get("runId").stringValue());
  var corrected=request("POST","/api/v1/reconciliation-runs",registration.replace("a".repeat(64),"b".repeat(64)));assertEquals(201,corrected.statusCode());assertNotEquals(id,JSON.readTree(corrected.body()).get("runId").stringValue());
  assertEquals(2,JSON.readTree(request("GET","/api/v1/reconciliation-runs?businessDate=2026-10-01",null).body()).size());
  assertEquals(2,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
 }
 @Test void validationRejectsWholeReportsWithoutPartialWritesAndPagingIsBounded()throws Exception{
  var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
  var collection=env.mongoDatabase().getCollection("reconciliation_runs");var before=collection.find().first();
  var valid=report(id);
  var duplicate=(tools.jackson.databind.node.ObjectNode)JSON.readTree(valid);((tools.jackson.databind.node.ArrayNode)duplicate.get("results")).add(duplicate.get("results").get(0));
  var missing=(tools.jackson.databind.node.ObjectNode)JSON.readTree(valid);((tools.jackson.databind.node.ArrayNode)missing.get("results").get(0).get("settlementEvidence")).removeAll();
  var badOutcome=(tools.jackson.databind.node.ObjectNode)JSON.readTree(valid);((tools.jackson.databind.node.ObjectNode)badOutcome.get("results").get(2)).put("outcome","MATCHED");
  for(var invalid:java.util.List.of(duplicate.toString(),missing.toString(),badOutcome.toString(),valid.replace("\"internalPurchaseCount\": 4","\"internalPurchaseCount\": 3"),valid.replace("40000","9223372036854775808"),valid.replace("40000","1.5"))){
   assertEquals(400,request("PUT",base+"/results",invalid).statusCode(),invalid);assertEquals(before,collection.find().first());
  }
  assertEquals(415,unsupportedBody(base+"/results").statusCode());
  assertEquals(200,request("PUT",base+"/results",valid).statusCode());
  var page=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?outcome=DUPLICATE&page=0&size=500",null).body());
  assertEquals(100,page.get("size").intValue());assertEquals(1,page.get("totalResults").intValue());assertEquals("DUP-001",page.get("results").get(0).get("reference").stringValue());
  var huge=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?page=2147483647&size=100",null).body());assertEquals(5,huge.get("totalResults").intValue());assertEquals(0,huge.get("results").size());
  for(var query:java.util.List.of("page=-1","size=0","size=-1","outcome=UNKNOWN"))assertEquals(400,request("GET","/api/v1/reconciliation-runs/"+id+"/results?"+query,null).statusCode());
 }
 static HttpResponse<String> unsupportedBody(String path)throws Exception{
  try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(env.apiBaseUri().resolve(path)).header("Authorization","Bearer "+LocalEnvironment.WORKER_TOKEN).header("Content-Type","text/plain").PUT(HttpRequest.BodyPublishers.ofString("invalid")).build(),HttpResponse.BodyHandlers.ofString());}
 }
 @Test void bsonApplicationLimitRejectsOversizedIdentityBeforeAnyBinding()throws Exception{
  var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;
  var collection=env.mongoDatabase().getCollection("reconciliation_runs");var before=collection.find().first();
  assertEquals(413,request("PUT",base+"/processing",object(id).replace("\"versionId\":\"v1\"","\"versionId\":\""+"X".repeat(8388608)+"\"")).statusCode());
  assertEquals(before,collection.find().first());
 }
 @Test void registrationRequiresClosedPastDateAndRealMongoRejectsBadTypes()throws Exception{
  var body="{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100}";
  assertEquals(404,request("POST","/api/v1/reconciliation-runs",body).statusCode());
  request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}");
  assertEquals(409,request("POST","/api/v1/reconciliation-runs",body).statusCode());
  assertEquals(409,request("POST","/api/v1/reconciliation-runs",body.replace("2026-10-01","2026-10-02")).statusCode());
  env.resetScenario();var id=prepare();var collection=env.mongoDatabase().getCollection("reconciliation_runs");var doc=collection.find().first();
  for(var invalid:java.util.List.of(new org.bson.Document("$set",new org.bson.Document("byteLength",1.5)),new org.bson.Document("$set",new org.bson.Document("status","INVALID")),new org.bson.Document("$set",new org.bson.Document("status","COMPLETED")))){
   assertThrows(com.mongodb.MongoWriteException.class,()->collection.updateOne(new org.bson.Document("_id",id),invalid));assertEquals(doc,collection.find().first());
  }
  var explain=collection.find(new org.bson.Document("businessDate","2026-10-01")).sort(new org.bson.Document("createdAt",-1).append("_id",1)).explain();
  assertTrue(explain.toJson().contains("runs_by_business_date"),explain.toJson());
  System.out.println("Task6 run listing explain: "+explain.toJson());
 }

 @Test void oversizedFullPublicationRetainsBoundInputAndExposesNoPartialReport()throws Exception{
  var id=prepare();var base="/internal/v1/reconciliation-runs/"+id;var version="X".repeat(4194304);
  assertEquals(200,request("PUT",base+"/processing",object(id).replace("\"versionId\":\"v1\"","\"versionId\":\""+version+"\"")).statusCode());
  var collection=env.mongoDatabase().getCollection("reconciliation_runs");var before=collection.find().first();
  assertEquals(413,request("PUT",base+"/results",report(id).replace("\"versionId\": \"v1\"","\"versionId\": \""+version+"\"")).statusCode());
  assertEquals(before,collection.find().first());assertEquals(409,request("GET","/api/v1/reconciliation-runs/"+id+"/results",null).statusCode());
 }

 @Test void failedAttemptCanRetryTheSameBoundVersionAndEmptyDatePublishesEmptyPage()throws Exception{
  assertEquals(201,request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}").statusCode());
  assertEquals(200,request("POST","/api/v1/business-dates/2026-10-01/close","{}").statusCode());
  var registration=request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":1}");
  assertEquals(201,registration.statusCode());var id=JSON.readTree(registration.body()).get("runId").stringValue();var base="/internal/v1/reconciliation-runs/"+id;
  assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
  assertEquals(200,request("POST",base+"/failure","{\"code\":\"TRANSPORT\",\"retriable\":true,\"attemptId\":\"failed\"}").statusCode());
  assertEquals("FAILED",JSON.readTree(request("GET",base,null).body()).get("status").stringValue());
  assertEquals(409,request("GET","/api/v1/reconciliation-runs/"+id+"/results",null).statusCode());
  assertEquals(200,request("PUT",base+"/processing",object(id)).statusCode());
  var empty=(tools.jackson.databind.node.ObjectNode)JSON.readTree(report(id));
  empty.set("summary",JSON.readTree("{\"internalPurchaseCount\":0,\"settlementRowCount\":0,\"distinctSettlementReferenceCount\":0,\"totalResultCount\":0,\"outcomeCounts\":{\"MATCHED\":0,\"MISSING_IN_SETTLEMENT\":0,\"MISSING_INTERNALLY\":0,\"AMOUNT_MISMATCH\":0,\"DUPLICATE\":0}}"));
  empty.set("results",JSON.readTree("[]"));assertEquals(200,request("PUT",base+"/results",empty.toString()).statusCode());
  assertEquals(JSON.readTree("{\"page\":0,\"size\":50,\"totalResults\":0,\"results\":[]}"),JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results",null).body()));
  assertEquals(JSON.readTree("{\"page\":0,\"size\":50,\"totalResults\":0,\"results\":[]}"),JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?outcome=MATCHED",null).body()));
 }
}
