package org.octavio.paymentreconciliationsim.controller;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import org.octavio.paymentreconciliationsim.model.CreatePurchase;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class PurchaseRequestDecoderTest {
 private final JsonMapper mapper=JsonMapper.builder().build();
 private static final String VALID="{\"transactionReference\":\"Ref_1-a\",\"merchantId\":\"M\",\"businessDate\":\"2026-10-01\",\"amountCentavos\":9223372036854775807,\"currency\":\"ARS\"}";
 @Test void preservesSigned64BitMaximumAndAcceptsExactDate() {
  assertEquals(new CreatePurchase("Ref_1-a","M",LocalDate.of(2026,10,1),Long.MAX_VALUE,"ARS"),PurchaseRequestDecoder.purchase(mapper.readTree(VALID)));
  assertEquals(LocalDate.of(2026,10,1),PurchaseRequestDecoder.date(mapper.readTree("{\"businessDate\":\"2026-10-01\"}")));
 }
 @ParameterizedTest @MethodSource("invalidJson") void rejectsCoercionUnknownCardFieldsAndInvalidBusinessValues(String body) {
  assertEquals(400,assertThrows(ResponseStatusException.class,()->PurchaseRequestDecoder.purchase(mapper.readTree(body))).getStatusCode().value());
 }
 static Stream<String> invalidJson() {
  return Stream.concat(Stream.of("null","[]","{}",
    VALID.replace("9223372036854775807","1.5"),VALID.replace("9223372036854775807","1.0"),
    VALID.replace("9223372036854775807","1e3"),VALID.replace("9223372036854775807","9223372036854775808"),
    VALID.replace("9223372036854775807","\"1\""),VALID.replace("9223372036854775807","null"),
    VALID.replace("9223372036854775807","true"),VALID.replace("9223372036854775807","0"),
    VALID.replace("9223372036854775807","-1"),VALID.replace("Ref_1-a",""),VALID.replace("Ref_1-a","é"),
    VALID.replace("Ref_1-a","a".repeat(65)),VALID.replace("Ref_1-a","a b"),
    VALID.replace("\"M\"","\"\""),VALID.replace("\"M\"","\""+ "a".repeat(65)+"\""),
    VALID.replace("\"M\"","null"),VALID.replace("\"M\"","1"),VALID.replace("ARS","USD"),
    VALID.replace("2026-10-01","2026-02-29"),VALID.replace("2026-10-01","2026-13-01"),
    VALID.replace("2026-10-01","2026-1-01"),VALID.replace("2026-10-01","+10000-01-01"),
    VALID.replace("\"2026-10-01\"","null"),VALID.replace("\"2026-10-01\"","1"),
    VALID.replace("}",",\"cardNumber\":\"1234\"}"),VALID.replace("}",",\"receivedAt\":\"2026-10-01T00:00:00Z\"}")),
    Stream.of("transactionReference","merchantId","businessDate","amountCentavos","currency").map(f->{
      var json=JsonMapper.builder().build().readTree(VALID).deepCopy();
      ((tools.jackson.databind.node.ObjectNode)json).remove(f);return json.toString();
    }));
 }
 @Test void dateOnlyRequestRejectsExtendedIsoYears() {
  var json=mapper.readTree("{\"businessDate\":\"+10000-01-01\"}");
  assertEquals(400,assertThrows(ResponseStatusException.class,()->PurchaseRequestDecoder.date(json)).getStatusCode().value());
 }
 @ParameterizedTest @MethodSource("invalidDates") void rejectsMalformedDateRequests(String body) {
  assertEquals(400,assertThrows(ResponseStatusException.class,()->PurchaseRequestDecoder.date(mapper.readTree(body))).getStatusCode().value());
 }
 static Stream<String> invalidDates(){return Stream.of("null","[]","{}","{\"businessDate\":null}","{\"businessDate\":1}","{\"businessDate\":\"2026-02-30\"}","{\"businessDate\":\"2026-1-01\"}","{\"businessDate\":\"2026-10-01\",\"extra\":1}");}
}
