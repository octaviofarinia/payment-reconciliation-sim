package org.octavio.paymentreconciliationsim.acceptance.steps;

import java.net.http.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.cucumber.java.en.*;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

public final class RecoverySteps {
 private final ScenarioWorld world;
 private final JsonMapper json=JsonMapper.builder().build();
 private String originalVersion;
 public RecoverySteps(ScenarioWorld world) { this.world=world; }
 private HttpResponse<String> request(String method,String path,String body) throws Exception {
  try(var client=HttpClient.newHttpClient()) {
   return client.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path))
     .header("Content-Type","application/json").header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN)
     .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),
     HttpResponse.BodyHandlers.ofString());
  }
 }
 @When("I retain a header-only upload without delivering its event")
 public void retain() throws Exception {
  byte[] bytes;
  try(var input=getClass().getResourceAsStream("/fixtures/header-only.csv")) {
   assertNotNull(input);bytes=input.readAllBytes();
  }
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  var response=request("POST","/api/v1/reconciliation-runs",
    "{\"businessDate\":\"2026-10-01\",\"sha256\":\""+hash+"\",\"byteLength\":"+bytes.length+"}");
  assertEquals(201,response.statusCode());
  var registration=json.readTree(response.body());
  world.runId=registration.get("runId").stringValue();
  originalVersion=world.environment.s3().store(LocalEnvironment.BUCKET,registration.get("objectKey").stringValue(),bytes);
 }
 @When("I request manual recovery with HTTP {int}")
 public void recover(int status) throws Exception {
  world.environment.awaitWorker();
  assertEquals(status,request("POST","/api/v1/reconciliation-runs/"+world.runId+"/reprocess","{}").statusCode());
 }
 @Then("recovery publishes one completed report for the retained version")
 public void completed() throws Exception {
  world.environment.awaitWorker();
  var run=json.readTree(request("GET","/api/v1/reconciliation-runs/"+world.runId,null).body());
  assertEquals("COMPLETED",run.get("status").stringValue());
  assertFalse(run.get("recoveryNeeded").booleanValue());
  assertEquals(originalVersion,run.get("objectIdentity").get("versionId").stringValue());
  assertEquals(1,world.environment.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
 }
}
