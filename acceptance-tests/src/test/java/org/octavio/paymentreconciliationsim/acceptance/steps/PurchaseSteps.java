package org.octavio.paymentreconciliationsim.acceptance.steps;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.bson.Document;
import io.cucumber.java.en.*;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
public final class PurchaseSteps {
 private final ScenarioWorld world;
 public PurchaseSteps(ScenarioWorld world){this.world=world;}
 private void post(String path,String body)throws Exception{
  try(var client=HttpClient.newHttpClient()){
   world.response=client.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path))
    .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN).header("Content-Type","application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
  }
 }
 @Given("an OPEN business date {string}")
 public void openDate(String date)throws Exception{
  post("/api/v1/business-dates","{\"businessDate\":\""+date+"\"}");assertEquals(201,world.response.statusCode());
 }
 @When("I ingest the canonical purchases")
 public void canonical()throws Exception{
  try(var fixture=getClass().getResourceAsStream("/fixtures/canonical-purchases.json")){
   assertNotNull(fixture);var purchases=JsonMapper.builder().build().readTree(fixture);
   assertEquals(4,purchases.size());
   for(var purchase:purchases){post("/api/v1/transactions",purchase.toString());assertEquals(201,world.response.statusCode());}
  }
 }
 @When("I close purchase date {string}")
 public void closeDate(String date)throws Exception{post("/api/v1/business-dates/"+date+"/close","{}");}
 @Then("the closed purchase boundary contains {int} purchases")
 public void boundary(int count){
  var response=Document.parse(new String(world.response.body(),StandardCharsets.UTF_8));
  assertEquals("CLOSED",response.getString("state"));assertEquals(count,((Number)response.get("purchaseCount")).intValue());
  assertEquals("2026-10-02T15:00:00Z",response.getString("closedAt"));world.fixtures.put("closed-boundary",world.response.body());
 }
 @Then("the purchase boundary is unchanged")
 public void sameBoundary(){assertArrayEquals(world.fixtures.get("closed-boundary"),world.response.body());}
 @When("I submit purchase {string} for date {string} with amount {word}")
 public void submit(String reference,String date,String amount)throws Exception{
  post("/api/v1/transactions","{\"transactionReference\":\""+reference+"\",\"merchantId\":\"MERCHANT-001\",\"businessDate\":\""+date+"\",\"amountCentavos\":"+amount+",\"currency\":\"ARS\"}");
  if(world.response.statusCode()==201)world.fixtures.put("original-purchase",world.response.body());
 }
 @Then("the replayed purchase is unchanged")
 public void samePurchase(){assertArrayEquals(world.fixtures.get("original-purchase"),world.response.body());}
}
