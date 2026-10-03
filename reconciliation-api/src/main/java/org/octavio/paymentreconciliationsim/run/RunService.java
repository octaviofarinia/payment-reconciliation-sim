package org.octavio.paymentreconciliationsim.run;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.octavio.paymentreconciliationsim.businessdate.BusinessDateService;
import org.springframework.dao.DuplicateKeyException;
import org.octavio.paymentreconciliationsim.storage.SettlementStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
@Service
public class RunService {
 private final RunRepository repository;private final BusinessDateService dates;private final Clock clock;
 private final ReportValidator validator;private final ReportCanonicalizer canonicalizer;
 private final SettlementStorage storage;

 public RunService(RunRepository repository, BusinessDateService dates, Clock clock,
         ReportValidator validator, ReportCanonicalizer canonicalizer, SettlementStorage storage) {
  this.repository = repository;
  this.dates = dates;
  this.clock = clock;
  this.validator = validator;
  this.canonicalizer = canonicalizer;
  this.storage = storage;
 }

 public RegistrationResult register(RegisterRun request) {
  if (request == null || request.businessDate() == null || request.sha256() == null
          || !request.sha256().matches("[a-f0-9]{64}") || request.byteLength() < 1
          || request.byteLength() > 2097152) {
   throw invalid();
  }
  if (!request.businessDate().isBefore(LocalDate.now(clock.withZone(ZoneId.of("America/Buenos_Aires"))))) {
   throw conflict();
  }
  dates.closedInputs(request.businessDate());
  var id = UUID.randomUUID();
  var now = now();
  var run = new ReconciliationRun(id.toString(), "SIMULATED", request.businessDate().toString(),
          request.sha256(), "v1", request.byteLength(),
          "settlements/" + request.businessDate() + "/" + id + ".csv",
          Status.AWAITING_UPLOAD, null, null, null, now, now);
  boolean created = true;
  try {
   run = repository.insert(run);
  } catch (DuplicateKeyException duplicate) {
   run = repository.logical(request.businessDate().toString(), request.sha256());
   created = false;
  }
  if (run.byteLength() != request.byteLength()) {
   throw conflict();
  }
  var instructions = run.status() == Status.COMPLETED ? null
          : storage.upload(run.objectKey(), run.sha256(), run.byteLength());
  return new RegistrationResult(UUID.fromString(run.runId()), run.objectKey(), run.status(),
          instructions, created);
 }
 public RunMetadata metadata(UUID id){return metadata(required(id));}
 public List<RunMetadata> list(LocalDate date){return repository.list(date.toString()).stream().map(this::metadata).toList();}
 public RunInput input(UUID id){
  var run=required(id);return new RunInput(id,run.source(),run.businessDate(),run.sha256(),run.rulesVersion(),run.objectIdentity(),purchases(run));
 }
 public void markProcessing(UUID id,ObjectIdentity object){
  var run=required(id);validateObject(run,object);
  if(run.status()==Status.COMPLETED){if(!object.equals(run.objectIdentity()))throw conflict();return;}
  repository.checkSize(new ReconciliationRun(run.runId(),run.source(),run.businessDate(),run.sha256(),run.rulesVersion(),run.byteLength(),run.objectKey(),Status.PROCESSING,object,null,run.report(),run.createdAt(),now()));
  if(repository.processing(id.toString(),object,now())==null){
   run=required(id);
   if(run.status()!=Status.COMPLETED || !object.equals(run.objectIdentity()))throw conflict();
  }
 }
 public PublicationResult publish(UUID id,ReportSubmission report){
  var run=required(id);
  var expected=new InputIdentity(run.source(),run.businessDate(),run.sha256(),run.rulesVersion(),run.objectIdentity());
  if(report==null || !expected.equals(report.inputIdentity()) || run.objectIdentity()==null)throw conflict();
  validator.validate(report,purchases(run));
  var canonical=canonicalizer.canonicalize(report);var now=now();
  repository.checkSize(new ReconciliationRun(run.runId(),run.source(),run.businessDate(),run.sha256(),run.rulesVersion(),run.byteLength(),run.objectKey(),Status.COMPLETED,run.objectIdentity(),null,canonical,run.createdAt(),now));
  var updated=repository.publish(id.toString(),run.objectIdentity(),canonical,now);
  if(updated!=null)return new PublicationResult(true,metadata(updated));
  var current=required(id);
  if(current.status()!=Status.COMPLETED || !canonical.equals(current.report()))throw conflict();
  return new PublicationResult(false,metadata(current));
 }
 public void fail(UUID id,FailureSubmission error){
  if(error==null || error.code()==null || !error.code().matches("[A-Z0-9_]{1,64}") || error.attemptId()==null || error.attemptId().isEmpty() || error.attemptId().length()>64)throw invalid();
  if(repository.fail(id.toString(),error,now())==null)required(id);
 }
 public ResultPage results(UUID id,Outcome outcome,int page,int size){
  if(page<0 || size<1)throw invalid();
  if(required(id).status()!=Status.COMPLETED)throw conflict();
  return repository.results(id.toString(),outcome,page,Math.min(size,100));
 }
 private List<PurchaseInput> purchases(ReconciliationRun run){
  return dates.closedInputs(LocalDate.parse(run.businessDate())).stream().map(p->new PurchaseInput(p.transactionReference(),p.merchantId(),p.amountCentavos())).sorted(Comparator.comparing(PurchaseInput::reference)).toList();
 }
 private void validateObject(ReconciliationRun run,ObjectIdentity object){
  if(object==null || object.bucket()==null || object.bucket().isEmpty() || !run.objectKey().equals(object.key()) || object.versionId()==null || object.versionId().isEmpty() || !run.sha256().equals(object.sha256()))throw conflict();
 }
 private RunMetadata metadata(ReconciliationRun run){
  return new RunMetadata(UUID.fromString(run.runId()),run.source(),run.businessDate(),run.sha256(),run.rulesVersion(),run.byteLength(),run.objectKey(),run.status(),run.objectIdentity(),run.error(),run.report()==null?null:run.report().summary(),run.createdAt(),run.updatedAt(),run.status()!=Status.COMPLETED && run.updatedAt().isBefore(clock.instant().minus(Duration.ofMinutes(20))));
 }
 private ReconciliationRun required(UUID id){
  var run=repository.find(id.toString());if(run==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown reconciliation run");return run;
 }
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid run request");}
 private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Conflicting run state or input identity");}
}
