package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class ReportCanonicalizerTest {
 @Test void equivalentReorderedBusinessOutputHasEqualCanonicalValueAndRetainsEveryRow(){
  var id=new InputIdentity("SIMULATED","2026-10-01","a".repeat(64),"v1",new ObjectIdentity("bucket","key","v1","a".repeat(64)));
  var summary=new Summary(0,2,1,2,Map.of(Outcome.DUPLICATE,1,Outcome.MISSING_IN_SETTLEMENT,1));
  var duplicate=new Result("Z",Outcome.DUPLICATE,null,null,List.of(new SettlementRow(2,"Z",3),new SettlementRow(1,"Z",2)));
  var missing=new Result("A",Outcome.MISSING_IN_SETTLEMENT,4L,"M",List.of());
  var expected=new ReportSubmission(id,summary,List.of(missing,new Result("Z",Outcome.DUPLICATE,null,null,List.of(new SettlementRow(1,"Z",2),new SettlementRow(2,"Z",3)))));
  var canonicalizer=new ReportCanonicalizer();
  var canonical=canonicalizer.canonicalize(new ReportSubmission(id,summary,List.of(duplicate,missing)));
  assertEquals(expected,canonical);
  assertEquals(expected,canonicalizer.canonicalize(expected));
  assertThrows(UnsupportedOperationException.class,()->canonical.results().clear());
  assertThrows(UnsupportedOperationException.class,()->canonical.results().get(1).settlementEvidence().clear());
  assertThrows(UnsupportedOperationException.class,()->canonical.summary().outcomeCounts().clear());
 }
}
