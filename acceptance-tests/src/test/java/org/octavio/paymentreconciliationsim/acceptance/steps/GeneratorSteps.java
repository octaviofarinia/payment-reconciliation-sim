package org.octavio.paymentreconciliationsim.acceptance.steps;

import com.sun.net.httpserver.HttpServer;
import io.cucumber.java.en.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Observes the separate CLI process at its public HTTP boundary. */
public final class GeneratorSteps {
 private final ScenarioWorld world;
 private int generatorExitCode;
 private String cliOutput;
 private final List<String> generatorRequests=new CopyOnWriteArrayList<>();
 public GeneratorSteps(ScenarioWorld world){this.world=world;}

 @When("the actual canonical generator CLI runs through public APIs")
 public void generatorEntryPointVerifiesResultsThroughPublicApis()throws Exception{
  var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  try(var forwarding=HttpClient.newHttpClient()){
   proxy.createContext("/",exchange->{
    String path=exchange.getRequestURI().toString();generatorRequests.add(exchange.getRequestMethod()+" "+path);
    try{
     var builder=HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path)).timeout(Duration.ofSeconds(20));
     for(String name:List.of("Authorization","Content-Type")){String value=exchange.getRequestHeaders().getFirst(name);if(value!=null)builder.header(name,value);}
     var response=forwarding.send(builder.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
     exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();exchange.sendResponseHeaders(503,-1);}
    finally{exchange.close();}
   });proxy.start();
   var jar=Path.of("../scenario-generator/target/scenario-generator-0.0.1-SNAPSHOT-exec.jar").toAbsolutePath();assertTrue(Files.isRegularFile(jar),jar.toString());
   var log=Files.createTempFile("generator-cli",".log");
   var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),
    "--base-url","http://127.0.0.1:"+proxy.getAddress().getPort(),"--business-date","2026-10-01","--seed","42","--scenario","canonical")
    .redirectErrorStream(true).redirectOutput(log.toFile());
   process.environment().put("RECONCILIATION_DEMO_TOKEN",LocalEnvironment.DEMO_TOKEN);
   var child=process.start();
   try{
    assertTrue(child.waitFor(330,java.util.concurrent.TimeUnit.SECONDS),"Generator CLI exceeded its bounded window");
    generatorExitCode=child.exitValue();cliOutput=Files.readString(log);
   }finally{child.destroyForcibly();Files.deleteIfExists(log);}
  }finally{proxy.stop(0);}
 }
 @Then("the generator exits successfully and verifies exact business results")
 public void results()throws Exception{
  assertEquals(0,generatorExitCode,cliOutput);
  int privateWorkerRequestsFromGenerator=(int)generatorRequests.stream().filter(r->r.contains("/internal/") || r.contains("/worker/")).count();
  assertEquals(0,privateWorkerRequestsFromGenerator);
  assertTrue(generatorRequests.stream().allMatch(r->r.matches("POST /api/v1/business-dates|POST /api/v1/transactions|POST /api/v1/business-dates/2026-10-01/close|POST /api/v1/reconciliation-runs|GET /api/v1/reconciliation-runs/[a-f0-9-]+(?:/results\\?page=\\d+&size=100)?")),generatorRequests.toString());
  var runId=java.util.regex.Pattern.compile("Verified runId=([a-f0-9-]+) results=5").matcher(cliOutput);assertTrue(runId.find(),cliOutput);world.runId=runId.group(1);
  var json=JsonMapper.builder().build();
  try(var http=HttpClient.newHttpClient()){
   var metadata=json.readTree(http.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve("/api/v1/reconciliation-runs/"+world.runId)).header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
   var page=json.readTree(http.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve("/api/v1/reconciliation-runs/"+world.runId+"/results?size=100")).header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
   try(var fixture=getClass().getResourceAsStream("/fixtures/canonical-expected.json")){
    var expectedBusinessResults=json.readTree(fixture);var retrievedBusinessResults=json.createObjectNode().set("summary",metadata.get("summary")).set("results",page.get("results"));
    assertEquals(expectedBusinessResults,retrievedBusinessResults);
   }
   var identity=metadata.get("objectIdentity");
   byte[] uploaded=world.environment.s3().get(LocalEnvironment.BUCKET,identity.get("key").stringValue(),identity.get("versionId").stringValue());
   String expectedCsv="business_date,transaction_reference,amount_centavos,currency\n2026-10-01,MATCH-001,10000,ARS\n2026-10-01,EXT-001,30000,ARS\n2026-10-01,AMOUNT-001,45000,ARS\n2026-10-01,DUP-001,50000,ARS\n2026-10-01,DUP-001,50000,ARS\n";
   assertArrayEquals(expectedCsv.getBytes(StandardCharsets.UTF_8),uploaded);
   assertEquals(uploaded.length,metadata.get("byteLength").intValue());
   assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(uploaded)),metadata.get("sha256").stringValue());
  }
  world.environment.awaitWorker();
 }
}
