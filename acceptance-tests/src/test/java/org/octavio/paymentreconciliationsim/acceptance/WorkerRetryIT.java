package org.octavio.paymentreconciliationsim.acceptance;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import org.octavio.paymentreconciliationsim.worker.*;
import org.octavio.paymentreconciliationsim.worker.csv.SettlementCsvParser;
import org.octavio.paymentreconciliationsim.worker.domain.ReconciliationComparator;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient;
import org.octavio.paymentreconciliationsim.worker.storage.S3SettlementReader;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
/** Real API/Mongo publication behind a controlled transient HTTP transport fault. */
class WorkerRetryIT {
 static LocalEnvironment env;static final JsonMapper JSON=JsonMapper.builder().build();
 @BeforeAll static void start(){env=new LocalEnvironment();env.start();}
 @AfterAll static void stop(){if(env!=null)env.close();}
 String fixture(String name)throws Exception{try(var in=getClass().getResourceAsStream("/fixtures/"+name)){assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 HttpResponse<String> request(String method,String path,String body)throws Exception{
  try(var http=HttpClient.newHttpClient()){return http.send(HttpRequest.newBuilder(env.apiBaseUri().resolve(path))
   .header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN).header("Content-Type","application/json")
   .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
 }
 @Test void outageAndLostPublicationResponseRecoverByRetryingTheExactVersion()throws Exception{
  env.resetScenario();
  request("POST","/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}");
  for(var purchase:JSON.readTree(fixture("canonical-purchases.json")))assertEquals(201,request("POST","/api/v1/transactions",purchase.toString()).statusCode());
  request("POST","/api/v1/business-dates/2026-10-01/close","{}");
  byte[] bytes=fixture("canonical.csv").getBytes(StandardCharsets.UTF_8);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  var registration=JSON.readTree(request("POST","/api/v1/reconciliation-runs","{\"businessDate\":\"2026-10-01\",\"sha256\":\""+hash+"\",\"byteLength\":"+bytes.length+"}").body());
  String id=registration.get("runId").stringValue(),key=registration.get("objectKey").stringValue();
  String version=env.s3().store(LocalEnvironment.BUCKET,key,bytes);
  var event=Map.<String,Object>of("runId",id,"bucket",LocalEnvironment.BUCKET,"key",key,"versionId",version);
  try(var proxy=new FaultProxy(env.apiBaseUri());var s3=S3Client.builder().region(Region.US_EAST_1).endpointOverride(env.s3Endpoint()).forcePathStyle(true)
   .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("acceptance","acceptance"))).build();var http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()){
   var handler=new ReconciliationHandler(new ProcessingService(LocalEnvironment.BUCKET,new WorkerApiClient(proxy.uri(),LocalEnvironment.WORKER_TOKEN,http),
    new S3SettlementReader(s3),new SettlementCsvParser(),new ReconciliationComparator()));
   // API unavailable before metadata: invocation fails, no report or failure callback can be fabricated.
   assertThrows(IllegalStateException.class,()->handler.handleRequest(event,null));
   assertEquals("AWAITING_UPLOAD",JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body()).get("status").stringValue());
   assertEquals(409,request("GET","/api/v1/reconciliation-runs/"+id+"/results",null).statusCode());
   proxy.unavailable=false;
   // API commits a report, but the transport delivers a retryable error in place of its success response.
   assertThrows(IllegalStateException.class,()->handler.handleRequest(event,null));
   var completed=request("GET","/api/v1/reconciliation-runs/"+id,null).body();
   assertEquals("COMPLETED",JSON.readTree(completed).get("status").stringValue());
   env.s3().store(LocalEnvironment.BUCKET,key,"different latest bytes".getBytes());
   assertNull(handler.handleRequest(event,null));
   assertEquals(JSON.readTree(completed),JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id,null).body()));
   var results=JSON.readTree(request("GET","/api/v1/reconciliation-runs/"+id+"/results?size=100",null).body()).get("results");
   assertEquals(JSON.readTree(fixture("canonical-expected.json")).get("results"),results);
   assertEquals(2,proxy.publications);assertEquals(0,proxy.failureCallbacks);
   assertEquals(version,JSON.readTree(completed).get("objectIdentity").get("versionId").stringValue());
   assertEquals(1,env.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
  }
 }
 static final class FaultProxy implements AutoCloseable {
  final com.sun.net.httpserver.HttpServer server;final HttpClient http=HttpClient.newHttpClient();
  volatile boolean unavailable=true,losePublication=true;volatile int publications,failureCallbacks;
  FaultProxy(URI target)throws Exception{
   server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/",exchange->{
    try{
     String path=exchange.getRequestURI().getPath();
     if(path.endsWith("/failure"))failureCallbacks++;
     if(unavailable){exchange.sendResponseHeaders(503,-1);return;}
     byte[] body=exchange.getRequestBody().readAllBytes();
     var forwarded=HttpRequest.newBuilder(target.resolve(path)).header("Content-Type","application/json").header("Authorization","Bearer "+LocalEnvironment.WORKER_TOKEN)
      .method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(body)).build();
     var result=http.send(forwarded,HttpResponse.BodyHandlers.ofByteArray());
     if(path.endsWith("/results")&&exchange.getRequestMethod().equals("PUT")){
      publications++;
      if(losePublication){losePublication=false;assertEquals(200,result.statusCode());exchange.sendResponseHeaders(503,-1);return;}
     }
     exchange.sendResponseHeaders(result.statusCode(),result.body().length==0?-1:result.body().length);
     exchange.getResponseBody().write(result.body());
    }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.io.IOException(failure);}
    finally{exchange.close();}
   });server.start();
  }
  URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
  public void close(){server.stop(0);http.close();}
 }
}
