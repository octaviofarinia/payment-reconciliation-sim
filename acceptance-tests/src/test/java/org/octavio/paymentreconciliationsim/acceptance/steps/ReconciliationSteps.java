package org.octavio.paymentreconciliationsim.acceptance.steps;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import io.cucumber.java.en.*;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
/** Drives only public registration/upload and the same local Lambda entry point. */
public final class ReconciliationSteps {
 private final ScenarioWorld world;
 private final JsonMapper json=JsonMapper.builder().build();
 private String key,version;
 public ReconciliationSteps(ScenarioWorld world){this.world=world;}
 private HttpResponse<String> request(String method,String path,String body)throws Exception{
  try(var http=HttpClient.newHttpClient()){return http.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path))
    .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN).header("Content-Type","application/json")
    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
 }
 private byte[] fixture(String name)throws Exception{try(var in=getClass().getResourceAsStream("/fixtures/"+name)){assertNotNull(in,name);return in.readAllBytes();}}
 @Given("a closed reconciliation date with {string} purchases")
 public void closed(String purchases)throws Exception{
  assertEquals(201,request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}").statusCode());
  if(purchases.equals("canonical"))for(var purchase:json.readTree(fixture("canonical-purchases.json")))assertEquals(201,request("POST","/api/v1/transactions",purchase.toString()).statusCode());
  else assertEquals("empty",purchases);
  assertEquals(200,request("POST","/api/v1/business-dates/2026-10-01/close","{}").statusCode());
 }
 @When("I upload the {string} settlement fixture")
 public void upload(String name)throws Exception{
  byte[] bytes=fixture(name+".csv"),digest=MessageDigest.getInstance("SHA-256").digest(bytes);
  var registration=request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+HexFormat.of().formatHex(digest)+"\",\"byteLength\":"+bytes.length+"}");
  assertEquals(201,registration.statusCode(),registration.body());
  var node=json.readTree(registration.body());world.runId=node.get("runId").stringValue();key=node.get("objectKey").stringValue();
  assertEquals(409,request("GET","/api/v1/reconciliation-runs/"+world.runId+"/results",null).statusCode());
  try(var http=HttpClient.newHttpClient()){
   var response=http.send(HttpRequest.newBuilder(URI.create(node.get("uploadInstructions").get("url").stringValue()))
    .header("x-amz-checksum-sha256",Base64.getEncoder().encodeToString(digest)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());version=response.headers().firstValue("x-amz-version-id").orElseThrow();
  }
 }
 private JsonNode metadata()throws Exception{return json.readTree(request("GET","/api/v1/reconciliation-runs/"+world.runId,null).body());}
 @Then("the handler publishes committed {string} output")
 public void output(String name)throws Exception{
  world.environment.awaitWorker();var metadata=metadata();assertEquals("COMPLETED",metadata.get("status").stringValue());
  var results=request("GET","/api/v1/reconciliation-runs/"+world.runId+"/results?size=100",null);assertEquals(200,results.statusCode());
  var expected=json.readTree(fixture(name+"-expected.json"));
  var actual=json.createObjectNode().set("summary",metadata.get("summary")).set("results",json.readTree(results.body()).get("results"));
  assertEquals(expected,actual);assertEquals(expected.get("results").size(),json.readTree(results.body()).get("totalResults").intValue());
  assertEquals(version,metadata.get("objectIdentity").get("versionId").stringValue());
  assertEquals(1,world.environment.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
 }
 @Then("replaying the bound event preserves the committed report")
 public void replay()throws Exception{
  world.environment.awaitWorker();var before=metadata();
  world.environment.s3().store(LocalEnvironment.BUCKET,key,"unregistered latest bytes".getBytes(StandardCharsets.UTF_8));
  world.environment.invokeWorker(Map.of("Records",List.of(Map.of("eventSource","aws:s3","s3",Map.of("bucket",Map.of("name",LocalEnvironment.BUCKET),
    "object",Map.of("key",java.net.URLEncoder.encode(key,StandardCharsets.UTF_8),"versionId",version))))));
  assertEquals(before,metadata());assertEquals(1,world.environment.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
 }
 @Then("the handler records deterministic {string} without publishing")
 public void invalid(String code)throws Exception{
  world.environment.awaitWorker();var metadata=metadata();assertEquals("FAILED",metadata.get("status").stringValue());
  assertEquals(code,metadata.get("error").get("code").stringValue());assertFalse(metadata.get("error").get("retriable").booleanValue());
  assertFalse(metadata.get("error").get("attemptId").stringValue().isBlank());assertTrue(metadata.get("summary").isNull());
  assertEquals(409,request("GET","/api/v1/reconciliation-runs/"+world.runId+"/results",null).statusCode());
  assertEquals(version,metadata.get("objectIdentity").get("versionId").stringValue());
  assertNull(world.environment.mongoDatabase().getCollection("reconciliation_runs").find().first().get("report"));
  assertDoesNotThrow(()->world.environment.invokeWorker(Map.of("runId",world.runId,"bucket",LocalEnvironment.BUCKET,"key",key,"versionId",version)));
  assertEquals("FAILED",metadata().get("status").stringValue());
 }
}
