package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.storage.SettlementStorage;
import org.octavio.paymentreconciliationsim.worker.LambdaInvoker;
import org.octavio.paymentreconciliationsim.worker.LambdaInvoker.ManualInvocation;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class RecoveryServiceTest {
 final RunService runs=mock(RunService.class);
 final SettlementStorage storage=mock(SettlementStorage.class);
 final LambdaInvoker invoker=mock(LambdaInvoker.class);
 final RecoveryService service=new RecoveryService(runs,storage,invoker);
 RunMetadata metadata(Status status,ObjectIdentity identity) {
  return new RunMetadata(RunServiceTest.ID,"SIMULATED","2026-10-01",RunServiceTest.HASH,"v1",100,
    RunServiceTest.KEY,status,identity,null,null,RunServiceTest.NOW,RunServiceTest.NOW,true);
 }
 @Test void bindsRetainedOriginalAndAcceptsExactManualEvent() {
  for(var bound:Arrays.asList(null,RunServiceTest.OBJECT)) {
   var original=metadata(Status.FAILED,bound);var processing=metadata(Status.PROCESSING,RunServiceTest.OBJECT);
   when(runs.metadata(RunServiceTest.ID)).thenReturn(original,processing);
   when(storage.recoverUploadedVersion(original)).thenReturn(Optional.of(RunServiceTest.OBJECT));
   var result=service.reprocess(RunServiceTest.ID);
   assertTrue(result.accepted());assertEquals(processing,result.run());
   var order=inOrder(runs,invoker);
   order.verify(runs).markProcessing(RunServiceTest.ID,RunServiceTest.OBJECT);
   order.verify(invoker).invoke(new ManualInvocation(RunServiceTest.ID,"bucket",RunServiceTest.KEY,"version1"));
   clearInvocations(runs,storage,invoker);
  }
 }
 @Test void completedRecoveryAndConcurrentCompletionReturnUnchangedWithoutInvocation() {
  var completed=metadata(Status.COMPLETED,RunServiceTest.OBJECT);
  when(runs.metadata(RunServiceTest.ID)).thenReturn(completed);
  assertEquals(new ReprocessResult(false,completed),service.reprocess(RunServiceTest.ID));
  verifyNoInteractions(storage,invoker);
  var original=metadata(Status.PROCESSING,RunServiceTest.OBJECT);
  when(runs.metadata(RunServiceTest.ID)).thenReturn(original,completed);
  when(storage.recoverUploadedVersion(original)).thenReturn(Optional.of(RunServiceTest.OBJECT));
  assertEquals(new ReprocessResult(false,completed),service.reprocess(RunServiceTest.ID));
  verifyNoInteractions(invoker);
 }
 @Test void absentUnboundInputNeedsUploadButMissingBoundOriginalForbidsReplacement() {
  for(var bound:Arrays.asList(null,RunServiceTest.OBJECT)) {
   var original=metadata(Status.FAILED,bound);
   when(runs.metadata(RunServiceTest.ID)).thenReturn(original);
   when(storage.recoverUploadedVersion(original)).thenReturn(Optional.empty());
   var conflict=assertThrows(ResponseStatusException.class,()->service.reprocess(RunServiceTest.ID));
   assertEquals(409,conflict.getStatusCode().value());
   assertEquals(bound==null?"Upload needed: no verified retained settlement input":
     "Original bound settlement version is unavailable; replacement is forbidden",conflict.getReason());
  }
  verifyNoInteractions(invoker);
 }
 @Test void unavailableStorageOrAcceptanceReturnsSanitized503AndPreservesOriginalCausePrivacy() {
  var original=metadata(Status.FAILED,RunServiceTest.OBJECT);
  when(runs.metadata(RunServiceTest.ID)).thenReturn(original);
  when(storage.recoverUploadedVersion(original)).thenThrow(new IllegalStateException("secret transport url"));
  var storageFailure=assertThrows(ResponseStatusException.class,()->service.reprocess(RunServiceTest.ID));
  assertEquals(503,storageFailure.getStatusCode().value());assertNull(storageFailure.getCause());
  doReturn(Optional.of(RunServiceTest.OBJECT)).when(storage).recoverUploadedVersion(original);
  doThrow(new IllegalStateException("credential")).when(invoker).invoke(any());
  var failure=assertThrows(ResponseStatusException.class,()->service.reprocess(RunServiceTest.ID));
  assertEquals(503,failure.getStatusCode().value());assertNull(failure.getCause());
  assertFalse(failure.getMessage().contains("credential"));
 }
 @Test void unknownOrConflictingRunsPreserveTheirExistingHttpStatus() {
  var unknown=new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
  when(runs.metadata(RunServiceTest.ID)).thenThrow(unknown);
  assertSame(unknown,assertThrows(ResponseStatusException.class,()->service.reprocess(RunServiceTest.ID)));
  var original=metadata(Status.FAILED,null);
  doReturn(original).when(runs).metadata(RunServiceTest.ID);
  var conflict=new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
  when(storage.recoverUploadedVersion(original)).thenThrow(conflict);
  assertSame(conflict,assertThrows(ResponseStatusException.class,()->service.reprocess(RunServiceTest.ID)));
 }
}
