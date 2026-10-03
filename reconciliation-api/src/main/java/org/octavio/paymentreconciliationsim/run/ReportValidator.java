package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
/** Validates the full boundary and evidence, without calculating classifications. */
@Component
public class ReportValidator {
 public void validate(ReportSubmission report,List<PurchaseInput> purchases){
  require(report!=null);
  require(report.summary()!=null && report.results()!=null);
  require(report.results().size()<=3000);
  var internal=purchases.stream().collect(Collectors.toMap(PurchaseInput::reference,Function.identity()));
  var references=new HashSet<String>();var rows=new HashSet<Integer>();var counts=new EnumMap<Outcome,Integer>(Outcome.class);
  for(var outcome:Outcome.values())counts.put(outcome,0);
  int distinct=0;
  for(var result:report.results()){
   require(result!=null);require(result.reference()!=null && result.reference().matches("[A-Za-z0-9_-]{1,64}"));
   require(references.add(result.reference()));require(result.outcome()!=null && result.settlementEvidence()!=null);
   var purchase=internal.get(result.reference());
   if(purchase==null){require(result.internalAmountCentavos()==null && result.merchantId()==null);}
   else {require(Objects.equals(result.internalAmountCentavos(),purchase.amountCentavos()) && Objects.equals(result.merchantId(),purchase.merchantId()));}
   int evidence=result.settlementEvidence().size();
   switch(result.outcome()){
    case MATCHED, AMOUNT_MISMATCH -> require(purchase!=null && evidence==1);
    case MISSING_IN_SETTLEMENT -> require(purchase!=null && evidence==0);
    case MISSING_INTERNALLY -> require(purchase==null && evidence==1);
    default -> require(evidence>=2); // DUPLICATE is the remaining validated enum value.
   }
   if(evidence>0)distinct++;
   for(var row:result.settlementEvidence()){
    require(row!=null);require(row.rowNumber()>=1 && row.rowNumber()<=2000);
    require(rows.add(row.rowNumber()));require(result.reference().equals(row.reference()) && row.amountCentavos()>0);
   }
   counts.merge(result.outcome(),1,Integer::sum);
  }
  require(references.containsAll(internal.keySet()));
  // Unique bounded row numbers must cover exactly 1..N; missing rows cannot silently vanish.
  for(int row=1;row<=rows.size();row++)require(rows.contains(row));
  var expected=new Summary(purchases.size(),rows.size(),distinct,references.size(),counts);
  require(expected.equals(report.summary()));
 }
 private static void require(boolean valid){if(!valid)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid complete report");}
}
