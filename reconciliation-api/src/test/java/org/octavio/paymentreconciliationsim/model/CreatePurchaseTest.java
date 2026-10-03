package org.octavio.paymentreconciliationsim.model;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class CreatePurchaseTest {
 static final LocalDate DATE=LocalDate.of(2026,10,1);
 @Test void validatesDomainIndependentlyOfHttpAndComparesEveryBusinessField() {
  var request=new CreatePurchase("a".repeat(64),"m".repeat(64),DATE,Long.MAX_VALUE,"ARS");
  var purchase=new Purchase(request.transactionReference(),request.merchantId(),DATE.toString(),Long.MAX_VALUE,"ARS",Instant.EPOCH);
  assertTrue(request.matches(purchase));
  assertFalse(request.matches(new Purchase("other",request.merchantId(),DATE.toString(),Long.MAX_VALUE,"ARS",Instant.EPOCH)));
  assertFalse(request.matches(new Purchase(request.transactionReference(),"other",DATE.toString(),Long.MAX_VALUE,"ARS",Instant.EPOCH)));
  assertFalse(request.matches(new Purchase(request.transactionReference(),request.merchantId(),"2026-09-30",Long.MAX_VALUE,"ARS",Instant.EPOCH)));
  assertFalse(request.matches(new Purchase(request.transactionReference(),request.merchantId(),DATE.toString(),1,"ARS",Instant.EPOCH)));
  assertFalse(request.matches(new Purchase(request.transactionReference(),request.merchantId(),DATE.toString(),Long.MAX_VALUE,"USD",Instant.EPOCH)));
  assertTrue(new CreatePurchase("A","M",LocalDate.of(0,1,1),1,"ARS").businessDate().getYear()==0);
  assertEquals(9999,new CreatePurchase("A","M",LocalDate.of(9999,1,1),1,"ARS").businessDate().getYear());
 }
 @Test void preservesNonemptyWhitespaceMerchantCharacters() {
  assertEquals(" ",assertDoesNotThrow(()->new CreatePurchase("Ref"," ",DATE,1,"ARS")).merchantId());
 }
 @Test void merchantLimitCountsUnicodeCharactersWithoutNormalization() {
  String exact=new String(Character.toChars(0x1F4B3)).repeat(64);
  assertEquals(exact,assertDoesNotThrow(()->new CreatePurchase("Ref",exact,DATE,1,"ARS")).merchantId());
  assertBad("Ref",new String(Character.toChars(0x1F4B3)).repeat(65),DATE,1,"ARS");
 }
 @Test void nullAndOutOfRangeValuesCannotBypassValidation() {
  assertBad(null,"M",DATE,1,"ARS");assertBad("!","M",DATE,1,"ARS");
  assertBad("A",null,DATE,1,"ARS");assertBad("A","",DATE,1,"ARS");assertBad("A","m".repeat(65),DATE,1,"ARS");
  assertBad("A","M",null,1,"ARS");assertBad("A","M",LocalDate.of(-1,1,1),1,"ARS");assertBad("A","M",LocalDate.of(10000,1,1),1,"ARS");
  assertBad("A","M",DATE,0,"ARS");assertBad("A","M",DATE,-1,"ARS");assertBad("A","M",DATE,1,null);assertBad("A","M",DATE,1,"USD");
 }
 private void assertBad(String ref,String merchant,LocalDate date,long amount,String currency) {
  assertEquals(400,assertThrows(ResponseStatusException.class,()->new CreatePurchase(ref,merchant,date,amount,currency)).getStatusCode().value());
 }
}
