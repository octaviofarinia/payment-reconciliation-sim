package org.octavio.paymentreconciliationsim.worker.http;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient.*;
import org.octavio.paymentreconciliationsim.worker.domain.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkerApiClientTest {
 final HttpClient http=mock(HttpClient.class);final WorkerApiClient api=new WorkerApiClient(URI.create("http://localhost:8080"),"secret-token",http);
 static final JsonMapper JSON=JsonMapper.builder().build();
 @SuppressWarnings("unchecked") void response(int status,String body)throws Exception{var res=mock(HttpResponse.class);when(res.statusCode()).thenReturn(status);when(res.body()).thenReturn(body);when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(res);}
 HttpRequest request()throws Exception{var cap=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(cap.capture(),any(HttpResponse.BodyHandler.class));return cap.getValue();}
 @Test void metadataGetDecodesIndependentWireFieldsAndDeclaresReadTimeout()throws Exception{
  response(200,"{\"runId\":\"id\",\"source\":\"SIMULATED\",\"businessDate\":\"2026-10-01\",\"sha256\":\"hash\",\"rulesVersion\":\"v1\",\"byteLength\":123,\"objectKey\":\"key\",\"status\":\"PROCESSING\",\"objectIdentity\":null,\"createdAt\":\"ignored\"}");
  var meta=api.metadata("id");assertEquals(new Metadata("id","SIMULATED","2026-10-01","hash","v1",123,"key","PROCESSING",null),meta);
  var req=request();assertEquals(URI.create("http://localhost:8080/internal/v1/reconciliation-runs/id"),req.uri());assertEquals("GET",req.method());assertEquals(Optional.of(Duration.ofSeconds(20)),req.timeout());assertEquals(Optional.of("Bearer secret-token"),req.headers().firstValue("Authorization"));
 }
 @Test void inputGetDecodesCompletePurchaseList()throws Exception{
  response(200,"{\"runId\":\"id\",\"source\":\"SIMULATED\",\"businessDate\":\"2026-10-01\",\"sha256\":\"hash\",\"rulesVersion\":\"v1\",\"objectIdentity\":{\"bucket\":\"b\",\"key\":\"k\",\"versionId\":\"v\",\"sha256\":\"hash\"},\"purchases\":[{\"reference\":\"A\",\"merchantId\":\"M\",\"amountCentavos\":9223372036854775807}]}");
  var input=api.input("id");assertEquals("id",input.runId());assertEquals("SIMULATED",input.source());assertEquals("2026-10-01",input.businessDate());assertEquals("hash",input.sha256());assertEquals("v1",input.rulesVersion());assertEquals(new ObjectIdentity("b","k","v","hash"),input.objectIdentity());assertEquals(List.of(new ReconciliationModel.PurchaseInput("A","M",Long.MAX_VALUE)),input.purchases());
  assertTrue(request().uri().toString().endsWith("/id/input"));
 }
 @Test void writesTransmitRealJsonWithExplicitNullFieldsAndExactVerbs()throws Exception{
  try(var server=new TinyServer()){
   var client=new WorkerApiClient(server.uri(),"secret-token");
   var identity=new ObjectIdentity("b","k","v","hash");client.processing("id",identity);assertEquals("PUT /internal/v1/reconciliation-runs/id/processing",server.route);assertEquals(JSON.valueToTree(identity),JSON.readTree(server.body));assertEquals("Bearer secret-token",server.auth);
   var input=new InputIdentity("SIMULATED","2026-10-01","hash","v1",identity);
   var report=new ReconciliationComparator().compare(List.of(),List.of(new ReconciliationModel.SettlementRow(1,"EXTERNAL",99)));
   client.publish("id",new ReportSubmission(input,report.summary(),report.results()));assertEquals("PUT /internal/v1/reconciliation-runs/id/results",server.route);
   var node=JSON.readTree(server.body);assertEquals(JSON.valueToTree(input),node.get("inputIdentity"));assertTrue(node.get("results").get(0).has("merchantId"));assertTrue(node.get("results").get(0).get("merchantId").isNull());assertTrue(node.get("results").get(0).get("internalAmountCentavos").isNull());
   client.failure("id",new FailureSubmission("INVALID_HEADER",false,"attempt"));assertEquals("POST /internal/v1/reconciliation-runs/id/failure",server.route);assertEquals(JSON.readTree("{\"code\":\"INVALID_HEADER\",\"retriable\":false,\"attemptId\":\"attempt\"}"),JSON.readTree(server.body));

  }
 }
 @Test void defaultHttpTransportHasThreeSecondConnectTimeout(){
  var builder=mock(HttpClient.Builder.class);when(builder.connectTimeout(Duration.ofSeconds(3))).thenReturn(builder);when(builder.build()).thenReturn(http);
  try(var factory=mockStatic(HttpClient.class)){
   factory.when(HttpClient::newBuilder).thenReturn(builder);
   assertNotNull(new WorkerApiClient(URI.create("http://localhost"),"dummy"));verify(builder).connectTimeout(Duration.ofSeconds(3));verify(builder).build();
  }
 }
 @Test void statusAndBadJsonFailuresAreSanitizedAndRetryable()throws Exception{
  for(int status:new int[]{199,404,409,503,302}){response(status,"secret-token");var failure=assertThrows(ApiFailure.class,()->api.metadata("id"));assertEquals(status,failure.status());assertEquals("Worker API returned HTTP "+status,failure.getMessage());assertNull(failure.getCause());}
  for(String body:List.of("secret-token","null")){response(200,body);assertEquals("Invalid worker API response",assertThrows(IllegalStateException.class,()->api.metadata("id")).getMessage());}
  clearInvocations(http);response(200,"{}");api.processing("id",new ObjectIdentity("b","k","v","h"));assertTrue(request().headers().firstValue("Content-Type").get().contains("application/json"));
 }
 @Test void transportFailureAndInterruptionNeverIncludeToken()throws Exception{
  when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("secret-token"));
  assertEquals("Worker API transport failure",assertThrows(IllegalStateException.class,()->api.input("id")).getMessage());
  when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException("secret-token"));
  try{assertEquals("Worker API interrupted",assertThrows(IllegalStateException.class,()->api.input("id")).getMessage());assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
 }
 static class TinyServer implements AutoCloseable{
  final com.sun.net.httpserver.HttpServer server;volatile String route,body,auth;
  TinyServer()throws IOException{server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{route=e.getRequestMethod()+" "+e.getRequestURI().getPath();body=new String(e.getRequestBody().readAllBytes());auth=e.getRequestHeaders().getFirst("Authorization");e.sendResponseHeaders(200,2);e.getResponseBody().write("{}".getBytes());e.close();});server.start();}
  URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}public void close(){server.stop(0);}
 }
}
