package org.octavio.paymentreconciliationsim.worker.http;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.octavio.paymentreconciliationsim.worker.domain.ReconciliationModel.*;
import tools.jackson.databind.json.JsonMapper;
/** Worker-owned wire DTOs and bounded HTTP; responses and credentials never enter exceptions. */
public final class WorkerApiClient {
 public record ObjectIdentity(String bucket,String key,String versionId,String sha256) {}
 public record InputIdentity(String source,String businessDate,String sha256,String rulesVersion,ObjectIdentity objectIdentity) {}
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record Metadata(String runId,String source,String businessDate,String sha256,String rulesVersion,long byteLength,String objectKey,String status,ObjectIdentity objectIdentity) {}
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record RunInput(String runId,String source,String businessDate,String sha256,String rulesVersion,ObjectIdentity objectIdentity,List<PurchaseInput> purchases) {}
 public record ReportSubmission(InputIdentity inputIdentity,Summary summary,List<Result> results) {}
 public record FailureSubmission(String code,boolean retriable,String attemptId) {}
 public static final class ApiFailure extends RuntimeException {
  private final int status;
  public ApiFailure(int status) { super("Worker API returned HTTP " + status); this.status = status; }
  public int status() { return status; }
 }
 public static final class WorkerTransportFailure extends IllegalStateException {
  public WorkerTransportFailure(String message) { super(message); }
 }
 private final URI baseUri;
 private final String token;
 private final HttpClient http;
 private final JsonMapper json = JsonMapper.builder().build();
 public WorkerApiClient(URI baseUri,String token) {
  this(baseUri,token,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
 }
 public WorkerApiClient(URI baseUri,String token,HttpClient http) { this.baseUri = baseUri; this.token = token; this.http = http; }
 public Metadata metadata(String runId) { return decode(send("GET",runId,"",null),Metadata.class); }
 public RunInput input(String runId) { return decode(send("GET",runId,"/input",null),RunInput.class); }
 public void processing(String runId,ObjectIdentity identity) { send("PUT",runId,"/processing",identity); }
 public void publish(String runId,ReportSubmission report) { send("PUT",runId,"/results",report); }
 public void failure(String runId,FailureSubmission failure) { send("POST",runId,"/failure",failure); }
 private <T> T decode(String body,Class<T> type) {
  try {
   T value = json.readValue(body,type);
   if (value == null) throw new IllegalStateException();
   return value;
  } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid worker API response"); }
 }
 private String send(String method,String runId,String suffix,Object body) {
  var request = HttpRequest.newBuilder(baseUri.resolve("/internal/v1/reconciliation-runs/" + runId + suffix))
          .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer " + token).header("Content-Type","application/json")
          .method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
  HttpResponse<String> response;
  try { response = http.send(request,HttpResponse.BodyHandlers.ofString()); }
  catch (IOException failure) { throw new WorkerTransportFailure("Worker API transport failure"); }
  catch (InterruptedException interrupted) {
   Thread.currentThread().interrupt();
   throw new WorkerTransportFailure("Worker API interrupted");
  }
  if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ApiFailure(response.statusCode());
  return response.body();
 }
}
