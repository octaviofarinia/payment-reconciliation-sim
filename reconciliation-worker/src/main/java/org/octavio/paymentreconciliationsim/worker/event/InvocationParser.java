package org.octavio.paymentreconciliationsim.worker.event;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.octavio.paymentreconciliationsim.worker.InputRejected;
/** Validates each batch entry independently; S3 keys use form URL encoding exactly once. */
public final class InvocationParser {
 private static final Pattern KEY = Pattern.compile("^settlements/([0-9]{4}-[0-9]{2}-[0-9]{2})/([a-f0-9-]{36})\\.csv$");
 public record Invocation(ObjectReference reference, String error) {}
 public List<Invocation> parse(Map<String,Object> event) {
  if (event == null) return List.of(rejected("INVALID_INVOCATION"));
  if ("s3:TestEvent".equals(event.get("Event"))) return List.of(rejected("S3_TEST_EVENT"));
  if (event.containsKey("Records")) {
   if (!(event.get("Records") instanceof List<?> records) || records.isEmpty()) return List.of(rejected("INVALID_INVOCATION"));
   var result = new ArrayList<Invocation>();
   for (Object record : records) result.add(parseRecord(record));
   return List.copyOf(result);
  }
  return parseManual(event);
 }
 private List<Invocation> parseManual(Map<String,Object> event) {
  try {
   String id = text(event.get("runId"));
   String key = text(event.get("key"));
   if (!id.equals(runId(key))) throw new InputRejected("INVALID_KEY");
   return List.of(new Invocation(new ObjectReference(id,text(event.get("bucket")),key,text(event.get("versionId"))),null));
  } catch (InputRejected invalid) { return List.of(rejected(invalid.code())); }
 }
 private Invocation parseRecord(Object value) {
  try {
   var record = object(value);
   if (!"aws:s3".equals(record.get("eventSource"))) throw new InputRejected("INVALID_INVOCATION");
   if (record.containsKey("eventName") && !text(record.get("eventName")).startsWith("ObjectCreated:")) throw new InputRejected("INVALID_INVOCATION");
   var s3 = object(record.get("s3"));
   var bucket = object(s3.get("bucket"));
   var object = object(s3.get("object"));
   String encoded = text(object.get("key"));
   String key;
   try { key = URLDecoder.decode(encoded,StandardCharsets.UTF_8); }
   catch (IllegalArgumentException invalid) { throw new InputRejected("INVALID_KEY"); }
   return new Invocation(new ObjectReference(runId(key),text(bucket.get("name")),key,text(object.get("versionId"))),null);
  } catch (InputRejected invalid) { return rejected(invalid.code()); }
 }
 private static String runId(String key) {
  var match = KEY.matcher(key);
  if (!match.matches()) throw new InputRejected("INVALID_KEY");
  try {
   LocalDate.parse(match.group(1));
   String id = match.group(2);
   if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
   return id;
  } catch (IllegalArgumentException | java.time.DateTimeException invalid) { throw new InputRejected("INVALID_KEY"); }
 }
 private static String text(Object value) {
  if (!(value instanceof String text) || text.isBlank()) throw new InputRejected("INVALID_INVOCATION");
  return text;
 }
 private static Map<?,?> object(Object value) {
  if (!(value instanceof Map<?,?> map)) throw new InputRejected("INVALID_INVOCATION");
  return map;
 }
 private static Invocation rejected(String code) { return new Invocation(null,code); }
}
