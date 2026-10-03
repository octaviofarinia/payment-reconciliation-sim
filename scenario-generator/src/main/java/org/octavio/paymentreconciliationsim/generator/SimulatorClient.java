package org.octavio.paymentreconciliationsim.generator;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
/** Public HTTP calls only. Presigned uploads carry no API bearer token. */
public class SimulatorClient {
 private final URI base;private final String token;private final HttpClient http;
 private final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
 public SimulatorClient(URI base,String token,HttpClient http){this.base=base;this.token=token;this.http=http;}
 public void createDate(LocalDate date){post("/api/v1/business-dates",Map.of("businessDate",date.toString()));}
 public void createPurchase(Purchase purchase){post("/api/v1/transactions",purchase);}
 public void closeDate(LocalDate date){post("/api/v1/business-dates/"+date+"/close",Map.of());}
 public Registration register(LocalDate date,String sha256,int byteLength){
  var body=new LinkedHashMap<String,Object>();body.put("businessDate",date.toString());body.put("sha256",sha256);body.put("byteLength",byteLength);
  return json.readValue(post("/api/v1/reconciliation-runs",body),Registration.class);
 }
 public void upload(UploadInstructions upload,byte[] bytes){
  var request=HttpRequest.newBuilder(URI.create(upload.url())).timeout(Duration.ofSeconds(20));
  String length=upload.requiredHeaders().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("content-length")).map(Map.Entry::getValue).findFirst().orElseThrow(()->new IllegalStateException("Upload content-length missing"));
  if(!Integer.toString(bytes.length).equals(length))throw new IllegalStateException("Upload content-length mismatch");
  // The known-length publisher supplies Content-Length; JDK restricted-header protection stays enabled.
  upload.requiredHeaders().forEach((name,value)->{if(!name.equalsIgnoreCase("content-length"))request.header(name,value);});
  send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),"Upload");
 }
 public RunMetadata metadata(String runId){return metadata(runId,Duration.ofSeconds(20));}
 public RunMetadata metadata(String runId,Duration remaining){return json.readValue(send(api("/api/v1/reconciliation-runs/"+runId).timeout(remaining.compareTo(Duration.ofSeconds(20))<0?remaining:Duration.ofSeconds(20)).GET().build(),"API"),RunMetadata.class);}
 public List<Result> results(String runId){
  var results=new ArrayList<Result>();int page=0;int total=-1;
  do{
   var response=json.readValue(get("/api/v1/reconciliation-runs/"+runId+"/results?page="+page+"&size=100"),ResultPage.class);
   if(response.page()!=page || response.totalResults()<0)throw new IllegalStateException("Invalid result page");
   if(total!=-1 && total!=response.totalResults())throw new IllegalStateException("Result total changed");
   total=response.totalResults();results.addAll(response.results());
   if(results.size()>total || (response.results().isEmpty() && results.size()<total))throw new IllegalStateException("Incomplete result page");
   page++;
  }while(results.size()<total);
  return List.copyOf(results);
 }
 private String post(String path,Object body){return send(api(path).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),"API");}
 private String get(String path){return send(api(path).GET().build(),"API");}
 private HttpRequest.Builder api(String path){return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).header("Content-Type","application/json");}
 private String send(HttpRequest request,String operation){
  try{
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()<200 || response.statusCode()>=300)throw new IllegalStateException(operation+" HTTP "+response.statusCode());
   return response.body();
  }catch(IOException failure){throw new IllegalStateException(operation+" transport failure");}
  catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(operation+" interrupted");}
 }
}
