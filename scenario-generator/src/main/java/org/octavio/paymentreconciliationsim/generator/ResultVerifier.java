package org.octavio.paymentreconciliationsim.generator;
import java.util.Comparator;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
public final class ResultVerifier {
 public void verify(ExpectedReport expected,ExpectedReport actual){
  if(!canonical(expected).equals(canonical(actual)))throw new IllegalStateException("Expected business output mismatch");
 }
 private ExpectedReport canonical(ExpectedReport report){
  return new ExpectedReport(report.summary(),report.results().stream().map(r->new Result(r.reference(),r.outcome(),r.internalAmountCentavos(),r.merchantId(),
   r.settlementEvidence().stream().sorted(Comparator.comparingInt(SettlementRow::rowNumber)).toList())).sorted(Comparator.comparing(Result::reference)).toList());
 }
}
