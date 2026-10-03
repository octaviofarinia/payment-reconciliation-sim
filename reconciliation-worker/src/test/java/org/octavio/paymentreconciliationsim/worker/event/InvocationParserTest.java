package org.octavio.paymentreconciliationsim.worker.event;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InvocationParserTest {
 static final String ID="12345678-1234-1234-1234-123456789abc";
 static final String KEY="settlements/2026-10-01/"+ID+".csv";
 final InvocationParser parser=new InvocationParser();
 static Map<String,Object> manual(){return new HashMap<>(Map.of("runId",ID,"bucket","bucket","key",KEY,"versionId","v+1"));}
 static Map<String,Object> record(String key){return Map.of("eventSource","aws:s3","eventName","ObjectCreated:Put","s3",Map.of("bucket",Map.of("name","bucket"),"object",Map.of("key",key,"versionId","v+1")));}
 @Test void manualAndRealEncodedKeysPreserveExactIdentity(){
  var expected=new ObjectReference(ID,"bucket",KEY,"v+1");
  assertEquals(expected,parser.parse(manual()).getFirst().reference());
  assertEquals(expected,parser.parse(Map.of("Records",List.of(record(KEY.replace("/","%2F"))),"extra",true)).getFirst().reference());
 }
 @Test void encodedPlusIsDecodedOnceAndNotConfusedWithSpace(){
  assertEquals("INVALID_KEY",parser.parse(Map.of("Records",List.of(record(KEY+"%2B")))).getFirst().error());
  assertEquals("INVALID_KEY",parser.parse(Map.of("Records",List.of(record(KEY+"+")))).getFirst().error());
  assertEquals("INVALID_KEY",parser.parse(Map.of("Records",List.of(record(KEY.replace("/","%252F"))))).getFirst().error());
 }
 @Test void setupTestEventIsExplicitlyRecognized(){assertEquals("S3_TEST_EVENT",parser.parse(Map.of("Event","s3:TestEvent")).getFirst().error());}
 @Test void malformedRecordsDoNotDropLaterValidRecords(){
  var result=parser.parse(Map.of("Records",Arrays.asList(null,Map.of(),record(KEY))));
  assertEquals(3,result.size());assertEquals("INVALID_INVOCATION",result.get(0).error());
  assertEquals("INVALID_INVOCATION",result.get(1).error());assertEquals(ID,result.get(2).reference().runId());
  assertThrows(UnsupportedOperationException.class,()->result.clear());
 }
 @Test void invalidEventShapesAreSafelyRejected(){
  for(var event:Arrays.<Map<String,Object>>asList(null,Map.of(),Map.of("Records","bad"),Map.of("Records",List.of()),Map.of("Event","wrong"))){
   assertEquals("INVALID_INVOCATION",parser.parse(event).getFirst().error());
  }
  for(Object value:Arrays.asList(null,1,""," ")){
   for(String field:List.of("runId","bucket","key","versionId")){
    var event=manual();event.put(field,value);assertNotNull(parser.parse(event).getFirst().error());
   }
  }
  for(String key:List.of("settlements/2026-02-30/"+ID+".csv","other/"+ID+".csv",KEY.replace(ID,"INVALID"),KEY.replace(ID,ID.toUpperCase()),KEY.replace(ID,"000000001-234-1234-1234-123456789abc"),KEY.replace(ID,"------------------------------------"))){
   var event=manual();event.put("key",key);assertNotNull(parser.parse(event).getFirst().error());
  }
  var mismatched=manual();mismatched.put("runId","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");assertEquals("INVALID_KEY",parser.parse(mismatched).getFirst().error());
 }
 @Test void onlyObjectCreationNotificationsAreSupported(){
  var valid=new HashMap<>(record(KEY));valid.put("eventName","ObjectCreated:CompleteMultipartUpload");
  assertEquals(ID,parser.parse(Map.of("Records",List.of(valid))).getFirst().reference().runId());
  valid.put("eventName","ObjectRemoved:Delete");assertEquals("INVALID_INVOCATION",parser.parse(Map.of("Records",List.of(valid))).getFirst().error());
 }
 @Test void unsupportedS3ShapesAndMissingVersionsAreRejected(){
  for(var record:List.of(Map.of("eventSource","other"),Map.of("eventSource","aws:s3","s3",Map.of()),Map.of("eventSource","aws:s3","s3","bad"),Map.of("eventSource","aws:s3","s3",Map.of("bucket","bad","object",Map.of())))){
   assertEquals("INVALID_INVOCATION",parser.parse(Map.of("Records",List.of(record))).getFirst().error());
  }
  var base=(Map<String,Object>)record(KEY);var s3=new HashMap<>((Map<String,Object>)base.get("s3"));
  s3.put("object",Map.of("key",KEY));
  assertEquals("INVALID_INVOCATION",parser.parse(Map.of("Records",List.of(Map.of("eventSource","aws:s3","s3",s3)))).getFirst().error());
  assertEquals("INVALID_KEY",parser.parse(Map.of("Records",List.of(record("%QZ")))).getFirst().error());
 }
}
