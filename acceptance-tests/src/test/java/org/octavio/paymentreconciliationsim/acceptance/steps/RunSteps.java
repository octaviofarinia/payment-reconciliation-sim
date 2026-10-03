package org.octavio.paymentreconciliationsim.acceptance.steps;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import io.cucumber.java.en.*;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
public final class RunSteps {
 private final ScenarioWorld world;private final JsonMapper json=JsonMapper.builder().build();
 public RunSteps(ScenarioWorld world){this.world=world;}
 private void request(String method,String path,String body)throws Exception{
  try(var client=HttpClient.newHttpClient()){world.response=client.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("Authorization","Bearer "+(path.startsWith("/internal/")?LocalEnvironment.WORKER_TOKEN:LocalEnvironment.DEMO_TOKEN)).method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());}
 }
 private String fixture(String name)throws Exception{try(var input=getClass().getResourceAsStream("/fixtures/"+name)){assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
 @When("I register the prepared canonical settlement")
 public void register()throws Exception{
  request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100}");
  assertEquals(201,world.response.statusCode());world.runId=json.readTree(world.response.body()).get("runId").stringValue();
 }
 @When("I request the prepared run results") public void results()throws Exception{request("GET","/api/v1/reconciliation-runs/"+world.runId+"/results?size=100",null);}
 @When("the worker publishes the prepared canonical report")
 public void publish()throws Exception{
  var key="settlements/2026-10-01/"+world.runId+".csv";
  request("PUT","/internal/v1/reconciliation-runs/"+world.runId+"/processing","{\"bucket\":\"acceptance-settlements\",\"key\":\""+key+"\",\"versionId\":\"v1\",\"sha256\":\""+"a".repeat(64)+"\"}");assertEquals(200,world.response.statusCode());
  request("PUT","/internal/v1/reconciliation-runs/"+world.runId+"/results",fixture("canonical-report.json").replace("OBJECT_KEY",key));assertEquals(200,world.response.statusCode());
 }
 @Then("the run contains the exact committed canonical business output")
 public void expected()throws Exception{
  request("GET","/api/v1/reconciliation-runs/"+world.runId,null);assertEquals(200,world.response.statusCode());
  var metadata=json.readTree(world.response.body());assertEquals("COMPLETED",metadata.get("status").stringValue());
  var expected=json.readTree(fixture("canonical-expected.json"));assertEquals(expected.get("summary"),metadata.get("summary"));
  results();assertEquals(200,world.response.statusCode());assertEquals(expected.get("results"),json.readTree(world.response.body()).get("results"));
 }
}
