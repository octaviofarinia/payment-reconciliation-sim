package org.octavio.paymentreconciliationsim.run;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class RunRequestDecoderTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 static final String OBJECT="{\"bucket\":\"bucket\",\"key\":\"key\",\"versionId\":\"v1\",\"sha256\":\""+"a".repeat(64)+"\"}";
 static final String REPORT="""
 {"inputIdentity":{"source":"SIMULATED","businessDate":"2026-10-01","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","rulesVersion":"v1","objectIdentity":%s},
 "summary":{"internalPurchaseCount":1,"settlementRowCount":1,"distinctSettlementReferenceCount":1,"totalResultCount":1,"outcomeCounts":{"MATCHED":1,"MISSING_IN_SETTLEMENT":0,"MISSING_INTERNALLY":0,"AMOUNT_MISMATCH":0,"DUPLICATE":0}},
 "results":[{"reference":"R","outcome":"MATCHED","internalAmountCentavos":9223372036854775807,"merchantId":"M","settlementEvidence":[{"rowNumber":1,"reference":"R","amountCentavos":9223372036854775807}]}]}
 """.formatted(OBJECT);
 @Test void decodesExactWireFieldsAndLongMaxMoney(){
  assertEquals(new RegisterRun(LocalDate.of(2026,10,1),"a".repeat(64),100),RunRequestDecoder.registration(JSON.readTree("{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100}")));
  assertEquals(new ObjectIdentity("bucket","key","v1","a".repeat(64)),RunRequestDecoder.object(JSON.readTree(OBJECT)));
  var report=RunRequestDecoder.report(JSON.readTree(REPORT));assertEquals(1,report.summary().internalPurchaseCount());assertEquals(1,report.results().getFirst().settlementEvidence().getFirst().rowNumber());assertEquals(Long.MAX_VALUE,report.results().getFirst().internalAmountCentavos());assertEquals(Long.MAX_VALUE,report.results().getFirst().settlementEvidence().getFirst().amountCentavos());
  var nullable=RunRequestDecoder.report(JSON.readTree(REPORT.replace("9223372036854775807,\"merchantId\":\"M\"","null,\"merchantId\":null")));assertNull(nullable.results().getFirst().internalAmountCentavos());assertNull(nullable.results().getFirst().merchantId());
  assertEquals(new FailureSubmission("TRANSPORT",true,"attempt"),RunRequestDecoder.failure(JSON.readTree("{\"code\":\"TRANSPORT\",\"retriable\":true,\"attemptId\":\"attempt\"}")));
 }
 @Test void rejectsCoercionOverflowMissingAndUnknownFields(){
  for(String bad:new String[]{"1.5","9223372036854775808","\"1\"","null","true"})invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("9223372036854775807",bad))));
  invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"rowNumber\":1","\"rowNumber\":2147483648"))));
  invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"rowNumber\":1","\"rowNumber\":-2147483649"))));
  invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"MATCHED\":1","\"MATCHED\":1.2"))));
  invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"outcome\":\"MATCHED\"","\"outcome\":\"OTHER\""))));
  invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"merchantId\":\"M\",",""))));
  invalid(()->RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"reference\":\"R\"","\"reference\":123"))));
  var nonArray=(tools.jackson.databind.node.ObjectNode)JSON.readTree(REPORT);nonArray.set("results",JSON.readTree("{}"));invalid(()->RunRequestDecoder.report(nonArray));
  invalid(()->RunRequestDecoder.object(JSON.readTree("{}")));invalid(()->RunRequestDecoder.object(JSON.readTree("[]")));
  invalid(()->RunRequestDecoder.registration(JSON.readTree("{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":1.5}")));
  invalid(()->RunRequestDecoder.failure(JSON.readTree("{\"code\":\"ERROR\",\"retriable\":\"true\",\"attemptId\":\"attempt\"}")));
  invalid(()->RunRequestDecoder.failure(JSON.readTree("{\"code\":\"ERROR\",\"retriable\":true,\"attemptId\":\"attempt\",\"extra\":1}")));
 }

 @Test void rejectsUnknownFieldsAtEveryNestedReportBoundary(){
  for(String path:new String[]{"root","identity","summary","counts","result","row"}){
   var root=(tools.jackson.databind.node.ObjectNode)JSON.readTree(REPORT);
   var node=switch(path){case "identity"->root.get("inputIdentity");case "summary"->root.get("summary");case "counts"->root.get("summary").get("outcomeCounts");case "result"->root.get("results").get(0);case "row"->root.get("results").get(0).get("settlementEvidence").get(0);default->root;};
   ((tools.jackson.databind.node.ObjectNode)node).put("unexpected",1);invalid(()->RunRequestDecoder.report(root));
  }
  invalid(()->RunRequestDecoder.registration(JSON.readTree("{\"businessDate\":\"2026-10-01\",\"sha256\":\""+"a".repeat(64)+"\",\"byteLength\":100,\"extra\":1}")));
  var root=(tools.jackson.databind.node.ObjectNode)JSON.readTree(REPORT);
  ((tools.jackson.databind.node.ObjectNode)root.get("results").get(0)).set("settlementEvidence",JSON.readTree("{}"));invalid(()->RunRequestDecoder.report(root));
 }
 @Test void integerDecoderPreservesSignedIntEndpointsForLaterBusinessValidation(){
  assertEquals(Integer.MAX_VALUE,RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"rowNumber\":1","\"rowNumber\":2147483647"))).results().getFirst().settlementEvidence().getFirst().rowNumber());
  assertEquals(Integer.MIN_VALUE,RunRequestDecoder.report(JSON.readTree(REPORT.replace("\"rowNumber\":1","\"rowNumber\":-2147483648"))).results().getFirst().settlementEvidence().getFirst().rowNumber());
 }
 static void invalid(Runnable call){assertEquals(400,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());}
}
