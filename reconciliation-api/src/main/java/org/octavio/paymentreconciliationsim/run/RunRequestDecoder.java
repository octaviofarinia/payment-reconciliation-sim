package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.octavio.paymentreconciliationsim.controller.PurchaseRequestDecoder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
/** Explicit tree decoding prevents fractional/overflow coercion and missing nullable fields. */
public final class RunRequestDecoder {
 private RunRequestDecoder(){}
 public static RegisterRun registration(JsonNode json){
  fields(json,"businessDate","sha256","byteLength");
  return new RegisterRun(PurchaseRequestDecoder.parseDate(text(json,"businessDate")),text(json,"sha256"),integer(json.get("byteLength")));
 }
 public static ObjectIdentity object(JsonNode json){
  fields(json,"bucket","key","versionId","sha256");return new ObjectIdentity(text(json,"bucket"),text(json,"key"),text(json,"versionId"),text(json,"sha256"));
 }
 public static ReportSubmission report(JsonNode json){
  fields(json,"inputIdentity","summary","results");
  var identity=json.get("inputIdentity");fields(identity,"source","businessDate","sha256","rulesVersion","objectIdentity");
  var input=new InputIdentity(text(identity,"source"),text(identity,"businessDate"),text(identity,"sha256"),text(identity,"rulesVersion"),object(identity.get("objectIdentity")));
  var summary=json.get("summary");fields(summary,"internalPurchaseCount","settlementRowCount","distinctSettlementReferenceCount","totalResultCount","outcomeCounts");
  var countJson=summary.get("outcomeCounts");fields(countJson,"MATCHED","MISSING_IN_SETTLEMENT","MISSING_INTERNALLY","AMOUNT_MISMATCH","DUPLICATE");
  var counts=new EnumMap<Outcome,Integer>(Outcome.class);for(var outcome:Outcome.values())counts.put(outcome,number(countJson.get(outcome.name())));
  var total=new Summary(number(summary.get("internalPurchaseCount")),number(summary.get("settlementRowCount")),number(summary.get("distinctSettlementReferenceCount")),number(summary.get("totalResultCount")),counts);
  array(json.get("results"));var results=new ArrayList<Result>();
  for(var result:json.get("results")){
   fields(result,"reference","outcome","internalAmountCentavos","merchantId","settlementEvidence");
   array(result.get("settlementEvidence"));var rows=new ArrayList<SettlementRow>();
   for(var row:result.get("settlementEvidence")){fields(row,"rowNumber","reference","amountCentavos");rows.add(new SettlementRow(number(row.get("rowNumber")),text(row,"reference"),integer(row.get("amountCentavos"))));}
   Outcome outcome;try{outcome=Outcome.valueOf(text(result,"outcome"));}catch(IllegalArgumentException error){throw invalid();}
   var amount=result.get("internalAmountCentavos");var merchant=result.get("merchantId");
   results.add(new Result(text(result,"reference"),outcome,amount.isNull()?null:integer(amount),merchant.isNull()?null:text(result,"merchantId"),List.copyOf(rows)));
  }
  return new ReportSubmission(input,total,List.copyOf(results));
 }
 public static FailureSubmission failure(JsonNode json){
  fields(json,"code","retriable","attemptId");if(!json.get("retriable").isBoolean())throw invalid();
  return new FailureSubmission(text(json,"code"),json.get("retriable").booleanValue(),text(json,"attemptId"));
 }
 private static void fields(JsonNode json,String... expected){if(!json.isObject() || !Set.copyOf(json.propertyNames()).equals(Set.of(expected)))throw invalid();}
 private static String text(JsonNode json,String key){var value=json.get(key);if(!value.isString())throw invalid();return value.stringValue();}
 private static long integer(JsonNode value){if(!value.isIntegralNumber() || !value.canConvertToLong())throw invalid();return value.longValue();}
 private static int number(JsonNode value){long number=integer(value);if(number<Integer.MIN_VALUE || number>Integer.MAX_VALUE)throw invalid();return (int)number;}
 private static void array(JsonNode value){if(!value.isArray())throw invalid();}
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid run payload");}
}
