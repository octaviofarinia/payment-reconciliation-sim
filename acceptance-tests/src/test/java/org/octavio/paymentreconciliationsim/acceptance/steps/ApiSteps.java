package org.octavio.paymentreconciliationsim.acceptance.steps;
import java.net.http.*;
import java.util.*;
import io.cucumber.java.en.*;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
public final class ApiSteps {
 private final ScenarioWorld world;private final JsonMapper json=JsonMapper.builder().build();
 public ApiSteps(ScenarioWorld world){this.world=world;}
 @When("I call {string} {string} using {string} credentials with content type {string} and body {string}")
 public void request(String method,String path,String role,String type,String body)throws Exception{
  var builder=HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path)).header("Content-Type",type);
  if(!role.equals("missing"))builder.header("Authorization","Bearer "+switch(role){case "demo"->LocalEnvironment.DEMO_TOKEN;case "worker"->LocalEnvironment.WORKER_TOKEN;default->"wrong-token";});
  try(var http=HttpClient.newHttpClient()){world.response=http.send(builder.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());}
 }
 @Then("the sanitized API error has status {int} and code {string}")
 public void error(int status,String code){
  assertEquals(status,world.response.statusCode());var body=json.readTree(world.response.body());assertEquals(code,body.path("code").asText());assertFalse(body.path("message").asText().isEmpty());
  assertDoesNotThrow(()->UUID.fromString(body.path("correlationId").asText()));assertEquals(body.path("correlationId").asText(),world.response.headers().firstValue("X-Correlation-Id").orElseThrow());assertEquals(Set.of("code","message","correlationId"),body.propertyNames());
 }
 @Then("the public OpenAPI document contains no worker routes")
 public void docs(){assertEquals(200,world.response.statusCode());assertFalse(new String(world.response.body(),java.nio.charset.StandardCharsets.UTF_8).contains("/internal/v1/"));assertTrue(json.readTree(world.response.body()).path("paths").has("/api/v1/transactions"));}
}
