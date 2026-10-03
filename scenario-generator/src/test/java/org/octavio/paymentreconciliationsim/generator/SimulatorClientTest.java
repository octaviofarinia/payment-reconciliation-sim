package org.octavio.paymentreconciliationsim.generator;
import com.sun.net.httpserver.*;import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.time.LocalDate;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
class SimulatorClientTest {
 HttpServer server;SimulatorClient client;URI base;List<String> paths=new ArrayList<>();List<byte[]> bodies=new ArrayList<>();List<Headers> headers=new ArrayList<>();String response="{}";int status=200;
 @BeforeEach void setup()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{paths.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());bodies.add(exchange.getRequestBody().readAllBytes());headers.add(exchange.getRequestHeaders());var bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
  base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());client=new SimulatorClient(base,"secret-demo",HttpClient.newHttpClient());
 }
 @AfterEach void close(){server.stop(0);}
 @Test void uploadUsesExactBytesChecksumLengthAndOnlySuppliedHeaders(){
  byte[] bytes={0,13,10,(byte)255};String checksum;try{checksum=Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new AssertionError(e);}
  assertDoesNotThrow(()->client.upload(new UploadInstructions(base+"/signed?X-Amz-Signature=opaque",Map.of("content-length","4","x-amz-checksum-sha256",checksum,"Content-Type","text/csv")),bytes));
  assertArrayEquals(bytes,bodies.getFirst());assertEquals("4",headers.getFirst().getFirst("Content-Length"));assertEquals(checksum,headers.getFirst().getFirst("x-amz-checksum-sha256"));assertEquals("text/csv",headers.getFirst().getFirst("Content-Type"));assertNull(headers.getFirst().getFirst("Authorization"));
 }
 @Test void uploadRejectsLengthDisagreementBeforeSending(){
  assertThrows(IllegalStateException.class,()->client.upload(new UploadInstructions(base+"/signed",Map.of("Content-Length","5")),new byte[4]));assertTrue(paths.isEmpty());
 }
 @Test void publicRoutesAndRegistrationUseIndependentJson(){
  var date=LocalDate.of(2026,10,1);assertDoesNotThrow(()->{client.createDate(date);client.createPurchase(new Purchase("REF","merchant",date,Long.MAX_VALUE,"ARS"));client.closeDate(date);});
  response="{\"runId\":\"run\",\"objectKey\":\"key\",\"status\":\"PENDING_UPLOAD\",\"created\":true,\"uploadInstructions\":{\"url\":\"http://s3\",\"requiredHeaders\":{},\"expiresAt\":\"ignored\"}}";status=201;
  assertEquals("run",assertDoesNotThrow(()->client.register(date,"hash",123)).runId());
  assertEquals(List.of("POST /api/v1/business-dates","POST /api/v1/transactions","POST /api/v1/business-dates/2026-10-01/close","POST /api/v1/reconciliation-runs"),paths);
  assertEquals("Bearer secret-demo",headers.getFirst().getFirst("Authorization"));
  assertTrue(new String(bodies.get(1),StandardCharsets.UTF_8).contains("9223372036854775807"));
  assertEquals("{\"businessDate\":\"2026-10-01\",\"sha256\":\"hash\",\"byteLength\":123}",new String(bodies.get(3),StandardCharsets.UTF_8));
 }
 @Test void metadataReadsTopLevelFields(){
  response="{\"runId\":\"run\",\"status\":\"FAILED\",\"error\":{\"code\":\"INVALID_HEADER\",\"retriable\":false,\"attemptId\":\"ignored\"},\"summary\":null,\"recoveryNeeded\":true}";
  assertEquals(new RunMetadata("run","FAILED",new Failure("INVALID_HEADER",false),null),assertDoesNotThrow(()->client.metadata("run")));assertEquals(List.of("GET /api/v1/reconciliation-runs/run"),paths);
 }
 @Test void paginationRetrievesEveryResult(){
  server.removeContext("/");server.createContext("/",e->{
   boolean first=e.getRequestURI().getQuery().contains("page=0");paths.add(e.getRequestURI().getQuery());
   var results=new ArrayList<Result>();for(int i=first?0:100;i<(first?100:101);i++)results.add(new Result("R"+i,"MATCHED",(long)i+1,"m",List.of()));
   String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(new ResultPage(first?0:1,100,101,results));
   byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
  });
  var all=assertDoesNotThrow(()->client.results("run"));assertEquals(101,all.size());assertEquals("R0",all.getFirst().reference());assertEquals("R100",all.getLast().reference());assertEquals(101,all.stream().map(Result::reference).distinct().count());assertEquals(List.of("page=0&size=100","page=1&size=100"),paths);
 }
 @Test void httpFailuresExposeStatusWithoutResponseSecrets(){
  response="secret response token";status=409;assertEquals("API HTTP 409",assertThrows(IllegalStateException.class,()->client.createDate(LocalDate.of(2026,10,1))).getMessage());
  status=403;assertEquals("Upload HTTP 403",assertThrows(IllegalStateException.class,()->client.upload(new UploadInstructions(base+"/upload",Map.of("content-length","1")),new byte[]{1})).getMessage());
 }

 @Test void missingLengthAndMalformedPagesFailClearly(){
  assertEquals("Upload content-length missing",assertThrows(IllegalStateException.class,()->client.upload(new UploadInstructions(base+"/upload",Map.of()),new byte[0])).getMessage());
  for(String bad:List.of("{\"page\":1,\"size\":100,\"totalResults\":0,\"results\":[]}","{\"page\":0,\"size\":100,\"totalResults\":-1,\"results\":[]}","{\"page\":0,\"size\":100,\"totalResults\":1,\"results\":[]}","{\"page\":0,\"size\":100,\"totalResults\":0,\"results\":[{\"reference\":\"A\",\"outcome\":\"MATCHED\",\"internalAmountCentavos\":1,\"merchantId\":\"m\",\"settlementEvidence\":[]}]}")){
   response=bad;assertThrows(IllegalStateException.class,()->client.results("run"));
  }
  response="{\"page\":0,\"size\":100,\"totalResults\":0,\"results\":[]}";assertEquals(List.of(),client.results("run"));
 }
 @Test void changedTotalBetweenPagesFails(){
  server.removeContext("/");server.createContext("/",e->{boolean first=e.getRequestURI().getQuery().contains("page=0");String body="{\"page\":"+(first?0:1)+",\"size\":100,\"totalResults\":"+(first?2:3)+",\"results\":[{\"reference\":\"A\",\"outcome\":\"MATCHED\",\"internalAmountCentavos\":1,\"merchantId\":\"m\",\"settlementEvidence\":[]}]}";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
  assertEquals("Result total changed",assertThrows(IllegalStateException.class,()->client.results("run")).getMessage());
 }
 @Test void lowerHttpErrorAndTransportInterruptsAreSanitized()throws Exception{
  var mock=org.mockito.Mockito.mock(HttpClient.class);var failing=new SimulatorClient(base,"secret",mock);
  @SuppressWarnings("unchecked") HttpResponse<String> informational=org.mockito.Mockito.mock(HttpResponse.class);org.mockito.Mockito.when(informational.statusCode()).thenReturn(199);org.mockito.Mockito.when(mock.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(informational);
  assertEquals("API HTTP 199",assertThrows(IllegalStateException.class,()->failing.metadata("run")).getMessage());
  org.mockito.Mockito.when(mock.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new java.io.IOException("secret transport detail"));
  assertEquals("API transport failure",assertThrows(IllegalStateException.class,()->failing.metadata("run")).getMessage());
  org.mockito.Mockito.when(mock.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new InterruptedException("secret"));
  try{assertEquals("API interrupted",assertThrows(IllegalStateException.class,()->failing.metadata("run")).getMessage());assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
 }

 @Test void metadataTimeoutUsesRemainingWindowWithTwentySecondCap()throws Exception{
  var mock=org.mockito.Mockito.mock(HttpClient.class);
  @SuppressWarnings("unchecked") HttpResponse<String> response=org.mockito.Mockito.mock(HttpResponse.class);
  org.mockito.Mockito.when(response.statusCode()).thenReturn(200);org.mockito.Mockito.when(response.body()).thenReturn("{\"runId\":\"run\",\"status\":\"PROCESSING\"}");
  var requests=new ArrayList<HttpRequest>();
  org.mockito.Mockito.when(mock.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(i->{requests.add(i.getArgument(0));return response;});
  var timed=new SimulatorClient(base,"secret",mock);timed.metadata("run",java.time.Duration.ofSeconds(5));timed.metadata("run",java.time.Duration.ofSeconds(30));
  assertEquals(List.of(java.time.Duration.ofSeconds(5),java.time.Duration.ofSeconds(20)),requests.stream().map(r->r.timeout().orElseThrow()).toList());
 }
}
