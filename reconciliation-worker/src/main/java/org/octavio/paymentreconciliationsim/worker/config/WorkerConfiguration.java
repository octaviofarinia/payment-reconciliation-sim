package org.octavio.paymentreconciliationsim.worker.config;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.json.JsonMapper;
/** Secrets are loaded outside Terraform, never placed in environment variables or diagnostics. */
public record WorkerConfiguration(URI apiUri, String bucket, String workerToken) {
 public WorkerConfiguration {
  if (workerToken == null || !workerToken.matches("[!-~]{1,1024}")) throw invalid();
 }
 public static WorkerConfiguration load(Map<String,String> environment,S3Client s3) {
  URI api = apiUri(required(environment,"RECONCILIATION_API_URI"));
  String bucket = required(environment,"RECONCILIATION_CONFIG_BUCKET");
  String key = required(environment,"RECONCILIATION_CONFIG_KEY");
  if (!"runtime-config/worker.json".equals(key)) throw new IllegalArgumentException("Invalid runtime configuration key");
  byte[] bytes;
  try (var stream = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
   bytes = stream.readNBytes(4097);
  } catch (IOException | RuntimeException failure) {
   throw new IllegalStateException("Cannot load worker runtime configuration");
  }
  try {
   if (bytes.length > 4096) throw invalid();
   var json = JsonMapper.builder().build().readTree(bytes);
   if (!json.isObject() || json.size() != 1 || !json.has("workerToken") || !json.get("workerToken").isString()) throw invalid();
   return new WorkerConfiguration(api,bucket,json.get("workerToken").stringValue());
  } catch (RuntimeException invalid) { throw invalid(); }
 }
 public static String required(Map<String,String> environment,String name) {
  String value = environment.get(name);
  if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing worker setting: " + name);
  return value;
 }
 private static URI apiUri(String value) {
  try {
   var uri = URI.create(value);
   if (!java.util.Arrays.asList("http","https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
   return uri;
  } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid worker API URI"); }
 }
 private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid worker runtime configuration"); }
 @Override public String toString() { return "WorkerConfiguration[workerToken=<redacted>]"; }
}
