package org.octavio.paymentreconciliationsim.worker;
import java.io.*;
import java.time.LocalDate;
import java.util.Map;
import org.octavio.paymentreconciliationsim.worker.config.WorkerConfiguration;
import org.octavio.paymentreconciliationsim.worker.csv.*;
import org.octavio.paymentreconciliationsim.worker.domain.ReconciliationComparator;
import org.octavio.paymentreconciliationsim.worker.event.ObjectReference;
import org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient;
import org.octavio.paymentreconciliationsim.worker.storage.S3SettlementReader;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import static org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient.*;
/** Real worker orchestration. A report crosses the API boundary only after complete validation. */
public final class ProcessingService {
 private final String bucket;
 private final WorkerApiClient api;
 private final S3SettlementReader storage;
 private final SettlementCsvParser parser;
 private final ReconciliationComparator comparator;
 public ProcessingService(String bucket,WorkerApiClient api,S3SettlementReader storage,SettlementCsvParser parser,ReconciliationComparator comparator) {
  this.bucket = bucket; this.api = api; this.storage = storage; this.parser = parser; this.comparator = comparator;
 }
 public static ProcessingService cloud(Map<String,String> environment) {
  var region = Region.of(WorkerConfiguration.required(environment,"AWS_REGION"));
  var s3 = S3Client.builder().region(region).build();
  var configuration = WorkerConfiguration.load(environment,s3);
  return new ProcessingService(configuration.bucket(),new WorkerApiClient(configuration.apiUri(),configuration.workerToken()),
          new S3SettlementReader(s3),new SettlementCsvParser(),new ReconciliationComparator());
 }
 public void process(ObjectReference reference,AttemptContext attempt) {
  Metadata metadata;
  try { metadata = api.metadata(reference.runId()); }
  catch (ApiFailure failure) {
   if (failure.status() == 404) throw new InputRejected("UNREGISTERED_OBJECT");
   throw failure;
  }
  var identity = new ObjectIdentity(reference.bucket(),reference.key(),reference.versionId(),metadata.sha256());
  if (!metadata.runId().equals(reference.runId()) || !bucket.equals(reference.bucket())
          || !metadata.objectKey().equals(reference.key())
          || (metadata.objectIdentity() != null && !identity.equals(metadata.objectIdentity()))) throw new InputRejected("INPUT_IDENTITY_MISMATCH");
  try {
   byte[] bytes = storage.read(reference,metadata.byteLength(),metadata.sha256());
   api.processing(reference.runId(),identity);
   var input = api.input(reference.runId());
   var expected = new InputIdentity(metadata.source(),metadata.businessDate(),metadata.sha256(),metadata.rulesVersion(),identity);
   var actual = new InputIdentity(input.source(),input.businessDate(),input.sha256(),input.rulesVersion(),input.objectIdentity());
   if (!reference.runId().equals(input.runId()) || !expected.equals(actual)) throw new IllegalStateException("Worker API input identity changed");
   var rows = parser.parse(new ByteArrayInputStream(bytes),LocalDate.parse(metadata.businessDate()));
   var report = comparator.compare(input.purchases(),rows);
   api.publish(reference.runId(),new ReportSubmission(expected,report.summary(),report.results()));
  } catch (SettlementValidationException invalid) {
   api.failure(reference.runId(),new FailureSubmission(invalid.code(),false,attempt.attemptId()));
  } catch (InputRejected invalid) {
   api.failure(reference.runId(),new FailureSubmission(invalid.code(),false,attempt.attemptId()));
  } catch (IOException failure) { throw new IllegalStateException("Worker object transport failure"); }
 }
}
