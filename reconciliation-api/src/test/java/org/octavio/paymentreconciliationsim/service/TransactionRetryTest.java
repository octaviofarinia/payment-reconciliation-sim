package org.octavio.paymentreconciliationsim.service;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.mongodb.MongoException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class TransactionRetryTest {
 @Test void rechecksAfterTwoConflictsAndAllowsThirdAttempt() {
  List<Integer> delays=new ArrayList<>();var retry=new TransactionRetry(delays::add);var calls=new AtomicInteger();
  assertEquals("committed",retry.execute(()->{if(calls.incrementAndGet()<3)throw new MongoException(112,"write conflict");return "committed";}));
  assertEquals(3,calls.get());assertEquals(List.of(1,2),delays);
 }
 @Test void stopsAfterThreeAttemptsAndDoesNotDelayAfterExhaustion() {
  List<Integer> delays=new ArrayList<>();var retry=new TransactionRetry(delays::add);var calls=new AtomicInteger();
  var failure=assertThrows(ResponseStatusException.class,()->retry.execute(()->{calls.incrementAndGet();throw new DuplicateKeyException("race");}));
  assertEquals(409,failure.getStatusCode().value());assertEquals(3,calls.get());assertEquals(List.of(1,2),delays);
 }
 @Test void recognizesWrappedMongoLabelAndSpringTransientErrors() {
  var labeled=new MongoException(251,"abort");labeled.addLabel("TransientTransactionError");
  for(var error:List.of(new IllegalStateException(labeled),new TransientDataAccessResourceException("temporary"),new DuplicateKeyException("race"))) {
   var calls=new AtomicInteger();List<Integer> waits=new ArrayList<>();
   assertEquals(17,new TransactionRetry(waits::add).execute(()->{if(calls.incrementAndGet()==1)throw error;return 17;}));
   assertEquals(2,calls.get());assertEquals(List.of(1),waits);
  }
 }
 @Test void permanentErrorsPassThroughAndDelayFailureStopsImmediately() {
  var error=new IllegalArgumentException("permanent");var mongo=new MongoException(121,"validation");
  var retry=new TransactionRetry(i->fail("Permanent errors must not retry"));
  assertSame(error,assertThrows(IllegalArgumentException.class,()->retry.execute(()->{throw error;})));
  assertSame(mongo,assertThrows(MongoException.class,()->retry.execute(()->{throw mongo;})));
  assertEquals(9,retry.execute(()->9));
  var interrupted=new IllegalStateException("delay failed");var calls=new AtomicInteger();
  assertSame(interrupted,assertThrows(IllegalStateException.class,()->new TransactionRetry(i->{throw interrupted;}).execute(()->{calls.incrementAndGet();throw new MongoException(112,"conflict");})));
  assertEquals(1,calls.get());
 }
}
