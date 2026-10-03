package org.octavio.paymentreconciliationsim.generator;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
class ScenarioFactoryTest {
 private final ScenarioFactory factory=new ScenarioFactory();
 private final LocalDate date=LocalDate.of(2026,10,1);
 @Test void canonicalHasExactFiveOutcomesAndEvidence(){
  var scenario=assertDoesNotThrow(()->factory.generate(42,date,ScenarioKind.CANONICAL));
  assertEquals(List.of("MATCH-001","MISS-001","AMOUNT-001","DUP-001"),scenario.purchases().stream().map(Purchase::transactionReference).toList());
  assertEquals(List.of(10000L,30000L,45000L,50000L,50000L),scenario.settlement().stream().map(SettlementRow::amountCentavos).toList());
  var expected=factory.expected(scenario,ScenarioKind.CANONICAL);
  assertEquals(new Summary(4,5,4,5,Map.of("MATCHED",1,"MISSING_IN_SETTLEMENT",1,"MISSING_INTERNALLY",1,"AMOUNT_MISMATCH",1,"DUPLICATE",1)),expected.summary());
  assertEquals(new Result("DUP-001","DUPLICATE",50000L,"MERCHANT-001",List.of(new SettlementRow(4,"DUP-001",50000),new SettlementRow(5,"DUP-001",50000))),expected.results().get(1));
  assertNull(expected.results().get(2).internalAmountCentavos());
  assertEquals(List.of(),expected.results().get(4).settlementEvidence());
 }
 @Test void seededAllMatchedIsRepeatableAndNamespaced(){
  var a=assertDoesNotThrow(()->factory.generate(Long.MIN_VALUE,date,ScenarioKind.ALL_MATCHED));
  assertEquals(a,factory.generate(Long.MIN_VALUE,date,ScenarioKind.ALL_MATCHED));
  assertNotEquals(a,factory.generate(1,date,ScenarioKind.ALL_MATCHED));
  assertNotEquals(a.purchases().getFirst().transactionReference(),factory.generate(Long.MIN_VALUE,date.plusDays(1),ScenarioKind.ALL_MATCHED).purchases().getFirst().transactionReference());
  assertEquals(100,a.purchases().size());
  var report=factory.expected(a,ScenarioKind.ALL_MATCHED);
  assertEquals(100,report.summary().outcomeCounts().get("MATCHED"));
  for(int i=0;i<100;i++){
   var p=a.purchases().get(i);
   assertTrue(p.transactionReference().matches("[A-Za-z0-9_-]{1,64}"));
   assertTrue(p.amountCentavos()>0);
   assertEquals(new Result(p.transactionReference(),"MATCHED",p.amountCentavos(),p.merchantId(),List.of(new SettlementRow(i+1,p.transactionReference(),p.amountCentavos()))),report.results().get(i));
  }
 }
 @Test void csvAndChecksumAreExactAndRepeatable(){
  var writer=new SettlementWriter();
  var scenario=assertDoesNotThrow(()->factory.generate(42,date,ScenarioKind.CANONICAL));
  var bytes=writer.write(scenario);
  assertEquals("business_date,transaction_reference,amount_centavos,currency\n2026-10-01,MATCH-001,10000,ARS\n2026-10-01,EXT-001,30000,ARS\n2026-10-01,AMOUNT-001,45000,ARS\n2026-10-01,DUP-001,50000,ARS\n2026-10-01,DUP-001,50000,ARS\n",new String(bytes,StandardCharsets.UTF_8));
  assertArrayEquals(bytes,writer.write(assertDoesNotThrow(()->factory.generate(42,date,ScenarioKind.CANONICAL))));
  assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",writer.sha256("abc".getBytes(StandardCharsets.UTF_8)));
 }
 @Test void verificationIgnoresResultAndEvidenceOrderingButDetectsBusinessChanges(){
  var report=factory.expected(assertDoesNotThrow(()->factory.generate(42,date,ScenarioKind.CANONICAL)),ScenarioKind.CANONICAL);
  var reordered=new ArrayList<Result>();
  for(var r:report.results()){var evidence=new ArrayList<>(r.settlementEvidence());Collections.reverse(evidence);reordered.add(new Result(r.reference(),r.outcome(),r.internalAmountCentavos(),r.merchantId(),evidence));}
  Collections.reverse(reordered);
  var verifier=new ResultVerifier();
  assertDoesNotThrow(()->verifier.verify(report,new ExpectedReport(report.summary(),reordered)));
  assertThrows(IllegalStateException.class,()->verifier.verify(report,new ExpectedReport(report.summary(),List.of())));
  assertThrows(IllegalStateException.class,()->verifier.verify(report,new ExpectedReport(new Summary(0,0,0,0,Map.of()),report.results())));
 }

 @Test void fixtureAndDigestProviderFailuresAreVisible(){
  var broken=new ScenarioFactory(()->new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)){int closes;@Override public void close()throws java.io.IOException{if(++closes>1)throw new java.io.IOException("fixture close");}});
  assertThrows(java.io.UncheckedIOException.class,()->broken.expected(new Scenario(date,List.of(),List.of()),ScenarioKind.CANONICAL));
  try(var mocked=org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)){
   mocked.when(()->java.security.MessageDigest.getInstance("SHA-256")).thenThrow(new java.security.NoSuchAlgorithmException());
   assertThrows(IllegalStateException.class,()->new SettlementWriter().sha256(new byte[0]));
  }
 }
}
