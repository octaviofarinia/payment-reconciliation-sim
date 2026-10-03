package org.octavio.paymentreconciliationsim.worker.config;
import java.net.URI;
import java.util.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkerConfigurationTest {
 static Map<String,String> env(){return new HashMap<>(Map.of("AWS_REGION","us-east-1","RECONCILIATION_API_URI","http://127.0.0.1:8080","RECONCILIATION_CONFIG_BUCKET","private-bucket","RECONCILIATION_CONFIG_KEY","runtime-config/worker.json"));}
 static S3Client client(byte[] bytes){var s3=mock(S3Client.class);when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream(bytes)));return s3;}
 @Test void startupFetchesOnlyExactPrivateConfigObjectAndNeverExposesToken()throws Exception{
  var s3=client("{\"workerToken\":\"private-worker-token\"}".getBytes());
  var config=WorkerConfiguration.load(env(),s3);
  assertEquals(URI.create("http://127.0.0.1:8080"),config.apiUri());assertEquals("private-bucket",config.bucket());assertEquals("private-worker-token",config.workerToken());
  assertFalse(config.toString().contains("private-worker-token"));
  var request=org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);verify(s3).getObject(request.capture());
  assertEquals("private-bucket",request.getValue().bucket());assertEquals("runtime-config/worker.json",request.getValue().key());
 }
 @Test void maximumConfigBytesAreAcceptedWithBoundedRead(){
  String body="{\"workerToken\":\"dummy\"}";
  assertEquals("dummy",WorkerConfiguration.load(env(),client((body+" ".repeat(4096-body.length())).getBytes())).workerToken());
 }
 @Test void malformedSecretConfigurationIsSanitized(){
  for(String body:List.of("","bad","[]","{}","null","{\"workerToken\":null}","{\"workerToken\":1}","{\"mongoUri\":\"secret\"}","{\"workerToken\":\"\"}","{\"workerToken\":\" \"}","{\"workerToken\":\"private-worker-token\",\"mongoUri\":\"secret\"}","{\"workerToken\":\"x\\ny\"}","{\"workerToken\":\"x\\ry\"}","{\"workerToken\":\""+"x".repeat(1025)+"\"}","x".repeat(4097))){
   var error=assertThrows(IllegalArgumentException.class,()->WorkerConfiguration.load(env(),client(body.getBytes())));
   assertEquals("Invalid worker runtime configuration",error.getMessage());assertNull(error.getCause());
  }
 }
 @Test void missingAndUnsafeEnvironmentSettingsFailBeforeSecretFetch(){
  for(String field:List.of("RECONCILIATION_API_URI","RECONCILIATION_CONFIG_BUCKET","RECONCILIATION_CONFIG_KEY")){
   for(String value:Arrays.asList(null,""," ")){
    var env=env();env.put(field,value);assertThrows(IllegalArgumentException.class,()->WorkerConfiguration.load(env,mock(S3Client.class)));
   }
  }
  for(String value:List.of("relative","file:///tmp/file","http://user:secret@localhost","http://[","http:///missing","ftp://localhost")){
   var env=env();env.put("RECONCILIATION_API_URI",value);var error=assertThrows(IllegalArgumentException.class,()->WorkerConfiguration.load(env,mock(S3Client.class)));assertFalse(error.toString().contains("secret"));
  }
  var env=env();env.put("RECONCILIATION_CONFIG_KEY","settlements/wrong.json");assertThrows(IllegalArgumentException.class,()->WorkerConfiguration.load(env,mock(S3Client.class)));
 }
 @Test void injectedTokenMustBeExplicitAndSafe(){
  assertThrows(IllegalArgumentException.class,()->new WorkerConfiguration(URI.create("http://localhost"),"bucket",null));
  assertEquals("x".repeat(1024),new WorkerConfiguration(URI.create("https://localhost"),"bucket","x".repeat(1024)).workerToken());
 }
 @Test void configurationReadFailuresRemainRetryableAndSanitized(){
  var s3=mock(S3Client.class);when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().message("private-worker-token").statusCode(503).build());
  assertEquals("Cannot load worker runtime configuration",assertThrows(IllegalStateException.class,()->WorkerConfiguration.load(env(),s3)).getMessage());
  var stream=new InputStream(){public int read()throws IOException{throw new IOException("private-worker-token");}};
  when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),stream));
  assertEquals("Cannot load worker runtime configuration",assertThrows(IllegalStateException.class,()->WorkerConfiguration.load(env(),s3)).getMessage());
 }
}
