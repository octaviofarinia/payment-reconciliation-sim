package org.octavio.paymentreconciliationsim.businessdate;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.bson.Document;
import org.octavio.paymentreconciliationsim.model.*;
import org.octavio.paymentreconciliationsim.repository.PurchaseRepository;
import org.octavio.paymentreconciliationsim.service.TransactionRetry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BusinessDateServiceTest {
 static final LocalDate DATE=LocalDate.of(2026,10,1);static final Instant NOW=Instant.parse("2026-10-02T15:00:00.123456Z");
 final MongoTemplate mongo=mock(MongoTemplate.class);final PurchaseRepository purchases=mock(PurchaseRepository.class);
 final BusinessDateService service=new BusinessDateService(mongo,purchases,Clock.fixed(NOW,ZoneOffset.UTC),new TransactionRetry(i->{}));
 final BusinessDay open=new BusinessDay("2026-10-01",BusinessDay.State.OPEN,0,0,null);
 final BusinessDay closed=new BusinessDay("2026-10-01",BusinessDay.State.CLOSED,1,2,Instant.parse("2026-10-02T15:00:00.123Z"));
 @Test void explicitlyCreatesEmptyOpenDateAndReturnsExistingClosedDateWithoutReopening() {
  when(mongo.insert(open)).thenReturn(open).thenThrow(new DuplicateKeyException("existing"));
  when(mongo.findById("2026-10-01",BusinessDay.class)).thenReturn(closed);
  assertEquals(new CreationResult<>(open,true),service.create(DATE));assertEquals(new CreationResult<>(closed,false),service.create(DATE));
 }
 @Test void closeUpdatesTheSharedGuardAndIdempotentReplayRetainsBoundary() {
  when(mongo.findAndModify(any(Query.class),any(Update.class),any(FindAndModifyOptions.class),eq(BusinessDay.class)))
   .thenAnswer(inv->{assertEquals(new Document("_id","2026-10-01").append("state",BusinessDay.State.OPEN),((Query)inv.getArgument(0)).getQueryObject());
    var update=((Update)inv.getArgument(1)).getUpdateObject();
    assertEquals(new Document("state",BusinessDay.State.CLOSED).append("closedAt",closed.closedAt()),update.get("$set"));
    assertEquals(new Document("revision",1L),update.get("$inc"));assertTrue(((FindAndModifyOptions)inv.getArgument(2)).isReturnNew());return closed;})
   .thenReturn(null);
  when(mongo.findById("2026-10-01",BusinessDay.class)).thenReturn(closed);
  assertEquals(closed,service.close(DATE));assertEquals(closed,service.close(DATE));
 }
 @Test void reserveUsesAtomicOpenAndCapPredicateAndIncrementsCountWithRevision() {
  when(mongo.findAndModify(any(Query.class),any(Update.class),any(FindAndModifyOptions.class),eq(BusinessDay.class))).thenAnswer(inv->{
   assertEquals(new Document("_id","2026-10-01").append("state",BusinessDay.State.OPEN).append("purchaseCount",new Document("$lt",1000)),((Query)inv.getArgument(0)).getQueryObject());
   assertEquals(new Document("$inc",new Document("purchaseCount",1L).append("revision",1L)),((Update)inv.getArgument(1)).getUpdateObject());
   assertTrue(((FindAndModifyOptions)inv.getArgument(2)).isReturnNew());return open;
  });
  assertDoesNotThrow(()->service.reserve(DATE));
 }
 @Test void reserveRejectsFullOrClosedAndDistinguishesUnknownDate() {
  when(mongo.findById("2026-10-01",BusinessDay.class)).thenReturn(closed,open,null);
  assertStatus(409,()->service.reserve(DATE));assertStatus(409,()->service.reserve(DATE));assertStatus(404,()->service.reserve(DATE));
 }
 @Test void closedInputsIncludesAllStoredPurchasesAndIsImmutable() {
  var first=new Purchase("AbsentFromSettlement","M","2026-10-01",1,"ARS",Instant.EPOCH);
  var second=new Purchase("Matched","M","2026-10-01",2,"ARS",Instant.EPOCH);
  var mutable=new ArrayList<>(List.of(first,second));
  when(mongo.findById("2026-10-01",BusinessDay.class)).thenReturn(closed,open,null);
  when(purchases.findByBusinessDate("2026-10-01")).thenReturn(mutable);
  var inputs=service.closedInputs(DATE);assertEquals(List.of(first,second),inputs);mutable.clear();assertEquals(2,inputs.size());
  assertThrows(UnsupportedOperationException.class,()->inputs.add(first));
  assertStatus(409,()->service.closedInputs(DATE));assertStatus(404,()->service.closedInputs(DATE));
 }
 @Test void closeOfUnknownDateReturns404(){assertStatus(404,()->service.close(DATE));}
 private static void assertStatus(int code,Runnable operation){assertEquals(code,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value());}
}
