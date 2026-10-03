package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.springframework.stereotype.Component;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
@Component
public class ReportCanonicalizer {
 public ReportSubmission canonicalize(ReportSubmission report){
  var counts=new EnumMap<Outcome,Integer>(Outcome.class);counts.putAll(report.summary().outcomeCounts());
  var summary=report.summary();
  var results=report.results().stream().sorted(Comparator.comparing(Result::reference)).map(result->new Result(result.reference(),result.outcome(),result.internalAmountCentavos(),result.merchantId(),result.settlementEvidence().stream().sorted(Comparator.comparingInt(SettlementRow::rowNumber)).toList())).toList();
  return new ReportSubmission(report.inputIdentity(),new Summary(summary.internalPurchaseCount(),summary.settlementRowCount(),summary.distinctSettlementReferenceCount(),summary.totalResultCount(),Collections.unmodifiableMap(counts)),results);
 }
}
