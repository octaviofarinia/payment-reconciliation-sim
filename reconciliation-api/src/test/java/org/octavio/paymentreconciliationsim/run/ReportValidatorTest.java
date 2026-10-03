package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class ReportValidatorTest {
 final ReportValidator validator=new ReportValidator();
 static final PurchaseInput PURCHASE=new PurchaseInput("R","M",Long.MAX_VALUE);
 static final SettlementRow ROW=new SettlementRow(1,"R",Long.MAX_VALUE);
 static ReportSubmission report(Outcome outcome,Long amount,String merchant,List<SettlementRow> evidence){
  var counts=new EnumMap<Outcome,Integer>(Outcome.class);for(var o:Outcome.values())counts.put(o,0);counts.put(outcome,1);
  return new ReportSubmission(null,new Summary(amount==null?0:1,evidence.size(),evidence.isEmpty()?0:1,1,counts),List.of(new Result("R",outcome,amount,merchant,evidence)));
 }
 @Test void acceptsExactLongMaxMoneyAndCompleteOutcomeCounts(){assertDoesNotThrow(()->validator.validate(report(Outcome.MATCHED,Long.MAX_VALUE,"M",List.of(ROW)),List.of(PURCHASE)));}
 @Test void acceptsEveryOutcomeShapeIncludingUnknownDuplicate(){
  assertDoesNotThrow(()->validator.validate(report(Outcome.MISSING_IN_SETTLEMENT,Long.MAX_VALUE,"M",List.of()),List.of(PURCHASE)));
  assertDoesNotThrow(()->validator.validate(report(Outcome.MISSING_INTERNALLY,null,null,List.of(ROW)),List.of()));
  assertDoesNotThrow(()->validator.validate(report(Outcome.AMOUNT_MISMATCH,Long.MAX_VALUE,"M",List.of(new SettlementRow(1,"R",1))),List.of(PURCHASE)));
  assertDoesNotThrow(()->validator.validate(report(Outcome.DUPLICATE,null,null,List.of(ROW,new SettlementRow(2,"R",1))),List.of()));
  assertDoesNotThrow(()->validator.validate(report(Outcome.DUPLICATE,Long.MAX_VALUE,"M",List.of(ROW,new SettlementRow(2,"R",1))),List.of(PURCHASE)));
 }
 @Test void rejectsDuplicateLogicalReferencesAndAbsentPurchaseEvidence(){
  var valid=report(Outcome.MATCHED,Long.MAX_VALUE,"M",List.of(ROW));
  invalid(new ReportSubmission(null,valid.summary(),List.of(valid.results().getFirst(),valid.results().getFirst())),List.of(PURCHASE));
  invalid(report(Outcome.MISSING_INTERNALLY,null,null,List.of(ROW)),List.of(PURCHASE));
 }
 @Test void rejectsInvalidOutcomeEvidenceAndForgedInternalFields(){
  invalid(report(Outcome.MATCHED,null,null,List.of(ROW)),List.of());
  invalid(report(Outcome.MATCHED,Long.MAX_VALUE,"M",List.of()),List.of(PURCHASE));
  invalid(report(Outcome.MISSING_IN_SETTLEMENT,Long.MAX_VALUE,"M",List.of(ROW)),List.of(PURCHASE));
  invalid(report(Outcome.MISSING_INTERNALLY,Long.MAX_VALUE,"M",List.of(ROW)),List.of(PURCHASE));
  invalid(report(Outcome.DUPLICATE,null,null,List.of(ROW)),List.of());
  invalid(report(Outcome.AMOUNT_MISMATCH,null,null,List.of(ROW)),List.of());
  invalid(report(Outcome.MATCHED,1L,"M",List.of(ROW)),List.of(PURCHASE));
  invalid(report(Outcome.MATCHED,Long.MAX_VALUE,"WRONG",List.of(ROW)),List.of(PURCHASE));
  invalid(report(Outcome.MATCHED,null,"M",List.of(ROW)),List.of());
 }
 @Test void rejectsWrongRowsAndReferenceSyntax(){
  for(var row:List.of(new SettlementRow(0,"R",1),new SettlementRow(2001,"R",1),new SettlementRow(1,"OTHER",1),new SettlementRow(1,"R",0),new SettlementRow(1,"R",-1))){
   invalid(report(Outcome.MATCHED,Long.MAX_VALUE,"M",List.of(row)),List.of(PURCHASE));
  }
  invalid(report(Outcome.DUPLICATE,null,null,List.of(ROW,ROW)),List.of());
  for(String reference:List.of("","a b","á","X".repeat(65))){
   var source=report(Outcome.MISSING_IN_SETTLEMENT,Long.MAX_VALUE,"M",List.of());
   invalid(new ReportSubmission(null,source.summary(),List.of(new Result(reference,Outcome.MISSING_IN_SETTLEMENT,Long.MAX_VALUE,"M",List.of()))),List.of(PURCHASE));
  }
 }
 @Test void rejectsIncompleteCountsAndSummaryDisagreement(){
  var r=report(Outcome.MATCHED,Long.MAX_VALUE,"M",List.of(ROW));var s=r.summary();
  for(var changed:List.of(new Summary(0,1,1,1,s.outcomeCounts()),new Summary(1,0,1,1,s.outcomeCounts()),new Summary(1,1,0,1,s.outcomeCounts()),new Summary(1,1,1,0,s.outcomeCounts()),new Summary(1,1,1,1,Map.of()),new Summary(1,1,1,1,Map.of(Outcome.MATCHED,2,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,0,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0)))){
   invalid(new ReportSubmission(null,changed,r.results()),List.of(PURCHASE));
  }
 }
 @Test void rejectsNullReportPartsAndPreservesEmptyClosedDate(){
  var empty=new Summary(0,0,0,0,Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,0,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0));
  assertDoesNotThrow(()->validator.validate(new ReportSubmission(null,empty,List.of()),List.of()));
  invalid(null,List.of());invalid(new ReportSubmission(null,null,List.of()),List.of());invalid(new ReportSubmission(null,empty,null),List.of());
  invalid(new ReportSubmission(null,empty,Arrays.asList((Result)null)),List.of());
  invalid(new ReportSubmission(null,empty,List.of(new Result(null,Outcome.MATCHED,null,null,List.of()))),List.of());
  invalid(new ReportSubmission(null,empty,List.of(new Result("R",null,null,null,List.of()))),List.of());
  invalid(new ReportSubmission(null,empty,List.of(new Result("R",Outcome.MATCHED,null,null,null))),List.of());
  invalid(new ReportSubmission(null,empty,List.of(new Result("R",Outcome.MISSING_INTERNALLY,null,null,Arrays.asList((SettlementRow)null)))),List.of());
  invalid(new ReportSubmission(null,new Summary(0,0,0,0,null),List.of()),List.of());
 }

 @Test void rejectsOversizedResultSetAndMissingSideEvidence(){
  var source=report(Outcome.MATCHED,Long.MAX_VALUE,"M",List.of(ROW));
  invalid(new ReportSubmission(null,source.summary(),Collections.nCopies(3001,source.results().getFirst())),List.of(PURCHASE));
  invalid(report(Outcome.MATCHED,1L,"M",List.of(ROW)),List.of());
  invalid(report(Outcome.MISSING_IN_SETTLEMENT,null,null,List.of()),List.of());
  invalid(report(Outcome.MISSING_INTERNALLY,null,null,List.of()),List.of());
  invalid(report(Outcome.DUPLICATE,null,null,List.of(new SettlementRow(2,"R",1),new SettlementRow(3,"R",1))),List.of());
 }

 @Test void checksReferenceAndInternalAbsenceIndependentlyOfOtherwiseConsistentSummary(){
  var external=report(Outcome.MISSING_INTERNALLY,null,null,List.of(ROW));
  invalid(new ReportSubmission(null,external.summary(),List.of(new Result("a b",Outcome.MISSING_INTERNALLY,null,null,List.of(new SettlementRow(1,"a b",1))))),List.of());
  var duplicate=report(Outcome.DUPLICATE,null,null,List.of(ROW,new SettlementRow(2,"R",1)));
  invalid(new ReportSubmission(null,duplicate.summary(),List.of(new Result("R",Outcome.DUPLICATE,1L,null,duplicate.results().getFirst().settlementEvidence()))),List.of());
  var counts=Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,2,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0);
  invalid(new ReportSubmission(null,new Summary(0,2,2,1,counts),List.of(new Result("R",Outcome.MISSING_INTERNALLY,null,null,List.of(ROW)),new Result("R",Outcome.MISSING_INTERNALLY,null,null,List.of(new SettlementRow(2,"R",1))))),List.of());
 }
 @Test void retainsHighestAllowedRowAndResultCount(){
  var inputs=new ArrayList<PurchaseInput>();var results=new ArrayList<Result>();
  for(int i=1;i<=1000;i++){inputs.add(new PurchaseInput("I"+i,"M",1));results.add(new Result("I"+i,Outcome.MISSING_IN_SETTLEMENT,1L,"M",List.of()));}
  for(int i=1;i<=2000;i++)results.add(new Result("E"+i,Outcome.MISSING_INTERNALLY,null,null,List.of(new SettlementRow(i,"E"+i,1))));
  var counts=Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,1000,Outcome.MISSING_INTERNALLY,2000,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0);
  assertDoesNotThrow(()->validator.validate(new ReportSubmission(null,new Summary(1000,2000,2000,3000,counts),results),inputs));
 }

 @Test void rejectsIndependentlyMalformedEvidenceEvenWhenTheSummaryMatchesItsReportedCardinality(){
  var zeros=Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,0,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0);
  invalid(new ReportSubmission(null,new Summary(1,0,0,0,zeros),List.of()),List.of(PURCHASE));
  var dupCounts=Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,0,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,1);
  invalid(new ReportSubmission(null,new Summary(0,1,1,1,dupCounts),List.of(new Result("R",Outcome.DUPLICATE,null,null,List.of(ROW,ROW)))),List.of());
  invalid(report(Outcome.DUPLICATE,null,null,List.of(ROW,new SettlementRow(3,"R",1))),List.of());
  var tooMany=new ArrayList<Result>();for(int row=1;row<=2001;row++)tooMany.add(new Result("R"+row,Outcome.MISSING_INTERNALLY,null,null,List.of(new SettlementRow(row,"R"+row,1))));
  var externalCounts=Map.of(Outcome.MATCHED,0,Outcome.MISSING_IN_SETTLEMENT,0,Outcome.MISSING_INTERNALLY,2001,Outcome.AMOUNT_MISMATCH,0,Outcome.DUPLICATE,0);
  invalid(new ReportSubmission(null,new Summary(0,2001,2001,2001,externalCounts),tooMany),List.of());
 }
 void invalid(ReportSubmission submission,List<PurchaseInput> purchases){assertEquals(400,assertThrows(ResponseStatusException.class,()->validator.validate(submission,purchases)).getStatusCode().value());}
}
