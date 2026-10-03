package org.octavio.paymentreconciliationsim.repository;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.model.Purchase;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PurchaseRepositoryTest {
 @Test void usesGlobalReferenceIdentityAndCompleteDateQuerySortedByReference(){
  var mongo=mock(MongoTemplate.class);var repository=new PurchaseRepository(mongo);
  var p=new Purchase("Ref","M","2026-10-01",1,"ARS",Instant.EPOCH);
  when(mongo.findById("Ref",Purchase.class)).thenReturn(p);when(mongo.insert(p)).thenReturn(p);
  when(mongo.find(any(Query.class),eq(Purchase.class))).thenAnswer(inv->{
   var query=(Query)inv.getArgument(0);assertEquals(new Document("businessDate","2026-10-01"),query.getQueryObject());
   assertEquals(new Document("_id",1),query.getSortObject());return List.of(p);
  });
  assertSame(p,repository.findByReference("Ref"));assertSame(p,repository.insert(p));assertEquals(List.of(p),repository.findByBusinessDate("2026-10-01"));
 }
}
