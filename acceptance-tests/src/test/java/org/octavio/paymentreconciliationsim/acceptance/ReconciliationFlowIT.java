package org.octavio.paymentreconciliationsim.acceptance;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import org.octavio.paymentreconciliationsim.worker.ReconciliationHandler;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class ReconciliationFlowIT {
 static LocalEnvironment env;static final JsonMapper JSON=JsonMapper.builder().build();
 @BeforeAll static void start(){env=new LocalEnvironment();env.start();}
 @AfterAll static void stop(){if(env!=null)env.close();}
 @BeforeEach void reset(){env.resetScenario();}
 static String fixture(String name)throws Exception{try(var in=ReconciliationFlowIT.class.getResourceAsStream("/fixtures/"+name)){assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 static HttpResponse<String> request(String method,String path,String body)throws Exception{
  try(var http=HttpClient.newHttpClient()){return http.send(HttpRequest.newBuilder(env.apiBaseUri().resolve(path)).header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
 }
 @Test void realHandlerPublishesCanonicalFixture()throws Exception{
  request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}");
  for(var purchase:JSON.readTree(fixture("canonical-purchases.json")))assertEquals(201,request("POST","/api/v1/transactions",purchase.toString()).statusCode());
  request("POST","/api/v1/business-dates/2026-10-01/close","{}");
  byte[] bytes=fixture("canonical.csv").getBytes(StandardCharsets.UTF_8);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  var registration=JSON.readTree(request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+hash+"\",\"byteLength\":"+bytes.length+"}").body());
  String id=registration.get("runId").stringValue(),key=registration.get("objectKey").stringValue();
  String version;
  try(var http=HttpClient.newHttpClient()){
   var upload=http.send(HttpRequest.newBuilder(java.net.URI.create(registration.get("uploadInstructions").get("url").stringValue()))
     .header("x-amz-checksum-sha256",Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)))
     .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,upload.statusCode());version=upload.headers().firstValue("x-amz-version-id").orElseThrow();
  }
  env.awaitWorker();
  var resultsResponse=request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null);
  assertEquals(200,resultsResponse.statusCode());
  var metadata=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body());
  var actualCanonicalBusinessJson=JSON.createObjectNode().set("summary",metadata.get("summary")).set("results",JSON.readTree(resultsResponse.body()).get("results"));
  var expectedCanonicalBusinessJson=JSON.readTree(fixture("canonical-expected.json"));
  assertEquals(expectedCanonicalBusinessJson,actualCanonicalBusinessJson);
  assertEquals(5,metadata.get("summary").get("totalResultCount").intValue());
  assertEquals(version,metadata.get("objectIdentity").get("versionId").stringValue());
  env.s3().store(LocalEnvironment.BUCKET,key,"changed latest bytes".getBytes(StandardCharsets.UTF_8));
  env.invokeWorker(Map.of("runId",id,"bucket",LocalEnvironment.BUCKET,"key",key,"versionId",version));
  assertEquals(actualCanonicalBusinessJson.get("results"),JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null).body()).get("results"));
  assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
  assertEquals("COMPLETED",JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body()).get("status").stringValue());
 }
}
