package org.octavio.paymentreconciliationsim.acceptance;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class ApiBoundaryIT {
 static LocalEnvironment env; static final JsonMapper JSON=JsonMapper.builder().build();
 static final String RUN="/reconciliation-runs/00000000-0000-0000-0000-000000000001";
 @BeforeAll static void start(){env=new LocalEnvironment();env.start();}
 @AfterAll static void stop(){if(env!=null)env.close();}
 static HttpResponse<String> request(String method,String path,String token,String type,HttpRequest.BodyPublisher body)throws Exception{
  try(var client=HttpClient.newHttpClient()){
   var builder=HttpRequest.newBuilder(env.apiBaseUri().resolve(path)).header("Content-Type",type);
   if(token!=null)builder.header("Authorization","Bearer "+token);
   return client.send(builder.method(method,body).build(),HttpResponse.BodyHandlers.ofString());
  }
 }
 static void error(HttpResponse<String> response,int status,String code,String message){
  assertEquals(status,response.statusCode(),response.body());
  assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
  var body=JSON.readTree(response.body());assertEquals(code,body.path("code").asText());assertEquals(message,body.path("message").asText());
  assertDoesNotThrow(()->UUID.fromString(body.path("correlationId").asText()));
  assertEquals(body.path("correlationId").asText(),response.headers().firstValue("X-Correlation-Id").orElseThrow());
  assertEquals(Set.of("code","message","correlationId"),body.propertyNames());
 }
 @Test void missingAndWrongTokensFailOnEveryProtectedRoute()throws Exception{
  for(String prefix:List.of("/api/v1","/internal/v1"))for(String token:Arrays.asList(null,"wrong-token"))
   error(request("GET",prefix+RUN,token,"application/json",HttpRequest.BodyPublishers.noBody()),401,"UNAUTHORIZED","A valid bearer token is required");
 }
 @Test void servletPathVariantsCannotBypassTokens()throws Exception{
  for(String path:List.of("/api/v1;matrix/transactions","/api/v1/transactions;matrix","/%61pi/v1/transactions","/internal/v1;matrix"+RUN+"/processing"))
   error(request(path.contains("internal")?"PUT":"POST",path,null,"application/json",HttpRequest.BodyPublishers.ofString("{}")),401,"UNAUTHORIZED","A valid bearer token is required");
 }
 @Test void demoTokenCannotAuthorizeWorkerWrites()throws Exception{
  for(String suffix:List.of("/processing","/results","/failure"))
   error(request(suffix.equals("/failure")?"POST":"PUT","/internal/v1"+RUN+suffix,LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.ofString("{}")),403,"FORBIDDEN","The bearer token cannot access this route");
  error(request("GET","/internal/v1"+RUN+"/input",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.noBody()),403,"FORBIDDEN","The bearer token cannot access this route");
  error(request("GET","/api/v1"+RUN,LocalEnvironment.WORKER_TOKEN,"application/json",HttpRequest.BodyPublishers.noBody()),403,"FORBIDDEN","The bearer token cannot access this route");
 }
 @Test void errorsAreUniformSanitizedAndOversizedChunkedReportsFailBeforeDecoding()throws Exception{
  error(request("GET","/api/v1"+RUN,LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.noBody()),404,"NOT_FOUND","The requested resource was not found");
  error(request("POST","/api/v1/transactions",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.ofString("{bad secret-token")),400,"INVALID_REQUEST","The request is invalid");
  error(request("POST","/api/v1/transactions",LocalEnvironment.DEMO_TOKEN,"text/plain",HttpRequest.BodyPublishers.ofString("private")),415,"UNSUPPORTED_MEDIA_TYPE","The request content type is unsupported");
  error(request("PUT","/internal/v1"+RUN+"/results",LocalEnvironment.WORKER_TOKEN,"application/json",HttpRequest.BodyPublishers.ofString(" ".repeat(8388608))),400,"INVALID_REQUEST","The request is invalid");
  var oversized=" ".repeat(8388609).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  for(var body:List.of(HttpRequest.BodyPublishers.ofByteArray(oversized),HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(oversized))))
   error(request("PUT","/internal/v1"+RUN+"/results",LocalEnvironment.WORKER_TOKEN,"application/json",body),413,"PAYLOAD_TOO_LARGE","The request payload exceeds the application limit");
 }
 @Test void pendingResultsAndRecoveryUseStableConflictErrors()throws Exception{
  request("POST","/api/v1/business-dates",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.ofString("{\"businessDate\":\"2026-10-01\"}"));
  request("POST","/api/v1/business-dates/2026-10-01/close",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.noBody());
  var registered=request("POST","/api/v1/reconciliation-runs",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.ofString("{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":1}"));
  assertEquals(201,registered.statusCode(),registered.body());var path="/api/v1/reconciliation-runs/"+JSON.readTree(registered.body()).path("runId").asText();
  error(request("GET",path+"/results",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.noBody()),409,"CONFLICT","The request conflicts with the current resource state");
  error(request("POST",path+"/reprocess",LocalEnvironment.DEMO_TOKEN,"application/json",HttpRequest.BodyPublishers.noBody()),409,"UPLOAD_NEEDED","Upload needed: no verified retained settlement input");
 }
 @Test void publicOpenApiAndSwaggerRuntimeIntegration()throws Exception{
  var response=request("GET","/v3/api-docs",null,"application/json",HttpRequest.BodyPublishers.noBody());assertEquals(200,response.statusCode(),response.body());
  assertFalse(response.body().contains("/internal/v1/"));var docs=JSON.readTree(response.body());
  for(String path:List.of("/api/v1/business-dates","/api/v1/business-dates/{date}/close","/api/v1/transactions","/api/v1/reconciliation-runs","/api/v1/reconciliation-runs/{runId}","/api/v1/reconciliation-runs/{runId}/results","/api/v1/reconciliation-runs/{runId}/reprocess"))assertTrue(docs.path("paths").has(path),path);
  for(String schema:List.of("CreatePurchase","RegisterRun","BusinessDay","Purchase","RunMetadata","RegistrationResult","ResultPage","Result","Summary","ApiError"))assertTrue(docs.path("components").path("schemas").has(schema),schema);
  assertEquals("bearer",docs.path("components").path("securitySchemes").path("demoBearer").path("scheme").asText());
  for(var path:docs.path("paths"))for(var operation:path){
   assertEquals("demoBearer",operation.path("security").get(0).propertyNames().iterator().next());
   for(String status:List.of("400","401","403","404","409","413","415"))assertTrue(operation.path("responses").has(status),status);
  }
  assertTrue(docs.path("paths").path("/api/v1/transactions").path("post").path("responses").has("201"));
  assertTrue(docs.path("paths").path("/api/v1/reconciliation-runs/{runId}/reprocess").path("post").path("responses").has("202"));
  assertEquals("#/components/schemas/CreatePurchase",docs.path("paths").path("/api/v1/transactions").path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText());
  for(String path:List.of("/swagger-ui/index.html","/swagger-ui/swagger-ui.css","/swagger-ui/swagger-ui-bundle.js","/v3/api-docs/swagger-config"))assertEquals(200,request("GET",path,null,"application/json",HttpRequest.BodyPublishers.noBody()).statusCode(),path);
 }
}
