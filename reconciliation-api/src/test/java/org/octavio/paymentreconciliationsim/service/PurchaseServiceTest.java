package org.octavio.paymentreconciliationsim.service;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.model.*;
import org.octavio.paymentreconciliationsim.businessdate.BusinessDateService;
import org.octavio.paymentreconciliationsim.repository.PurchaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PurchaseServiceTest {
 static final LocalDate DATE=LocalDate.of(2026,10,1);
 static final Instant NOW=Instant.parse("2026-10-02T15:00:00.123456Z");
 final PurchaseRepository repository=mock(PurchaseRepository.class);
 final BusinessDateService dates=mock(BusinessDateService.class);
 final Transactions manager=new Transactions();
 final List<Integer> delays=new ArrayList<>();
 final PurchaseService service=new PurchaseService(repository,dates,new TransactionTemplate(manager),new TransactionRetry(delays::add),Clock.fixed(NOW,ZoneOffset.UTC));
 final CreatePurchase request=new CreatePurchase("Ref","Merchant",DATE,Long.MAX_VALUE,"ARS");
 final Purchase stored=new Purchase("Ref","Merchant","2026-10-01",Long.MAX_VALUE,"ARS",Instant.parse("2026-10-02T15:00:00.123Z"));
 @Test void createsExactIntegerPurchaseAndServerTimestampInsideCommittedTransaction() {
  when(repository.insert(any())).thenAnswer(inv-> {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return inv.getArgument(0);});
  doAnswer(inv->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(dates).reserve(DATE);
  assertEquals(new CreationResult<>(stored,true),service.create(request));
  assertEquals(1,manager.commits);assertEquals(0,manager.rollbacks);assertEquals(1,manager.begins);
  var order=inOrder(repository,dates);order.verify(repository).findByReference("Ref");order.verify(dates).reserve(DATE);order.verify(repository).insert(stored);
 }
 @Test void exactReplaySkipsGuardEvenAfterClosureAndReturnsOriginalTimestamp() {
  when(repository.findByReference("Ref")).thenReturn(stored);
  assertEquals(new CreationResult<>(stored,false),service.create(request));verifyNoInteractions(dates);verify(repository,never()).insert(any());assertEquals(1,manager.commits);
 }
 @Test void referenceConflictRollsBackAndCannotReserveAnotherDate() {
  when(repository.findByReference("Ref")).thenReturn(stored);
  var different=new CreatePurchase("Ref","Merchant",DATE.minusDays(1),Long.MAX_VALUE,"ARS");
  assertEquals(409,assertThrows(ResponseStatusException.class,()->service.create(different)).getStatusCode().value());
  assertEquals(1,manager.rollbacks);assertEquals(0,manager.commits);verifyNoInteractions(dates);
 }
 @Test void duplicateRaceRollsBackAndRechecksExistingReferenceBeforeReservation() {
  when(repository.findByReference("Ref")).thenReturn(null,stored);
  when(repository.insert(any())).thenThrow(new DuplicateKeyException("other transaction won"));
  assertEquals(new CreationResult<>(stored,false),service.create(request));
  assertEquals(2,manager.begins);assertEquals(1,manager.rollbacks);assertEquals(1,manager.commits);assertEquals(List.of(1),delays);
  verify(dates,times(1)).reserve(DATE);
 }
 @Test void closedFullAndUnknownDatesFailWithoutInsertAndRollBack() {
  for(int status:List.of(409,404)) {
   doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.valueOf(status))).when(dates).reserve(DATE);
   assertEquals(status,assertThrows(ResponseStatusException.class,()->service.create(request)).getStatusCode().value());
  }
  verify(repository,never()).insert(any());assertEquals(2,manager.rollbacks);assertEquals(0,manager.commits);assertTrue(delays.isEmpty());
 }
 static final class Transactions extends AbstractPlatformTransactionManager {
  int begins,commits,rollbacks;
  protected Object doGetTransaction(){return new Object();}
  protected void doBegin(Object transaction,TransactionDefinition definition){begins++;}
  protected void doCommit(DefaultTransactionStatus status){commits++;}
  protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
 }
}
