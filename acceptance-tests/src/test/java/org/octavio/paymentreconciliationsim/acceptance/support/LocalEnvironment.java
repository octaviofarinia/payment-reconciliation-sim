package org.octavio.paymentreconciliationsim.acceptance.support;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.octavio.paymentreconciliationsim.PaymentReconciliationSimApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/** Owns the suite's real local processes; Cucumber scenario state is held separately. */
public final class LocalEnvironment implements AutoCloseable {
    public static final String DEMO_TOKEN = "acceptance-demo-token";
    public static final String WORKER_TOKEN = "acceptance-worker-token";
    public static final String BUCKET = "acceptance-settlements";
    private final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0.14")).withReplicaSet();
    private final S3Stub s3 = new S3Stub();
    private final String databaseName = "acceptance_" + UUID.randomUUID().toString().replace("-", "");
    private final String[] extraArguments;
    private final java.util.function.BooleanSupplier dockerAvailable;
    private MongoClient mongoClient;
    private ConfigurableApplicationContext context;
    private URI apiBaseUri;
    private Thread shutdownHook;
    private volatile boolean closed;
    private boolean resourcesClosed;
    // Allow the complete five-minute invocation plus local scheduling/cleanup margin.
    private static final long WORKER_WAIT_SECONDS = 330;
    private final java.util.concurrent.ExecutorService workerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.Future<?>> workerInvocations = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private software.amazon.awssdk.services.s3.S3Client workerS3;
    private java.net.http.HttpClient workerHttp;
    private org.octavio.paymentreconciliationsim.worker.ReconciliationHandler workerHandler;

    public LocalEnvironment(String... extraArguments) {
        this(() -> DockerClientFactory.instance().isDockerAvailable(), extraArguments);
    }

    LocalEnvironment(java.util.function.BooleanSupplier dockerAvailable, String... extraArguments) {
        this.dockerAvailable = dockerAvailable;
        this.extraArguments = extraArguments.clone();
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("The local environment is already closed");
        if (context != null) return;
        shutdownHook = new Thread(this::close, "acceptance-environment-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            if (!dockerAvailable.getAsBoolean()) {
                throw new IllegalStateException("Docker is required for local acceptance tests; start Docker and rerun acceptance-tests");
            }
            mongo.start();
            mongoClient = MongoClients.create(mongo.getReplicaSetUrl(databaseName));
            s3.start();
            var application = new SpringApplication(PaymentReconciliationSimApplication.class, FixedClockConfiguration.class);
            application.addInitializers(initialized -> {
                context = initialized;
                initialized.getBeanFactory().registerSingleton("localLambdaInvoker",
                    (org.octavio.paymentreconciliationsim.worker.LambdaInvoker) invocation -> enqueueWorker(java.util.Map.of(
                        "runId",invocation.runId().toString(),"bucket",invocation.bucket(),
                        "key",invocation.key(),"versionId",invocation.versionId())));
            });
            application.addListeners((ApplicationListener<WebServerInitializedEvent>) event ->
                    apiBaseUri = URI.create("http://127.0.0.1:" + event.getWebServer().getPort()));
            var arguments = new java.util.ArrayList<String>();
            arguments.add("--server.port=0");
            arguments.add("--spring.mongodb.uri=" + mongo.getReplicaSetUrl(databaseName));
            arguments.add("--reconciliation.security.demo-token=" + DEMO_TOKEN);
            arguments.add("--reconciliation.security.worker-token=" + WORKER_TOKEN);
            arguments.add("--reconciliation.aws.region=us-east-1");
            arguments.add("--reconciliation.aws.access-key=acceptance");
            arguments.add("--reconciliation.aws.secret-key=acceptance");
            arguments.add("--reconciliation.s3.endpoint=" + s3Endpoint());
            arguments.add("--reconciliation.s3.bucket=" + BUCKET);
            arguments.addAll(java.util.List.of(extraArguments));
            context = application.run(arguments.toArray(String[]::new));
            int port = ((WebServerApplicationContext)context).getWebServer().getPort();
            apiBaseUri = URI.create("http://127.0.0.1:" + port);
            // Explicit dummy credentials: never resolve the host's AWS configuration.
            workerS3 = software.amazon.awssdk.services.s3.S3Client.builder()
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1).endpointOverride(s3Endpoint())
                    .forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("acceptance", "acceptance"))).build();
            workerHttp = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var configuration = new org.octavio.paymentreconciliationsim.worker.config.WorkerConfiguration(apiBaseUri, BUCKET, WORKER_TOKEN);
            workerHandler = new org.octavio.paymentreconciliationsim.worker.ReconciliationHandler(
                    new org.octavio.paymentreconciliationsim.worker.ProcessingService(configuration.bucket(),
                            new org.octavio.paymentreconciliationsim.worker.http.WorkerApiClient(configuration.apiUri(), configuration.workerToken(), workerHttp),
                            new org.octavio.paymentreconciliationsim.worker.storage.S3SettlementReader(workerS3),
                            new org.octavio.paymentreconciliationsim.worker.csv.SettlementCsvParser(),
                            new org.octavio.paymentreconciliationsim.worker.domain.ReconciliationComparator()));
            s3.onUpload(upload -> enqueueWorker(java.util.Map.of(
                    "Records", java.util.List.of(java.util.Map.of("eventSource", "aws:s3", "eventName", "ObjectCreated:Put",
                            "s3", java.util.Map.of("bucket", java.util.Map.of("name", upload.bucket()),
                                    "object", java.util.Map.of("key", java.net.URLEncoder.encode(upload.key(), java.nio.charset.StandardCharsets.UTF_8),
                                            "versionId", upload.versionId())))))));
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public URI apiBaseUri() { return apiBaseUri; }
    public URI s3Endpoint() { return s3.endpoint(); }
    public S3Stub s3() { return s3; }
    public MongoClient mongoClient() { return mongoClient; }
    public MongoDatabase mongoDatabase() { return mongoClient.getDatabase(databaseName); }
    public org.octavio.paymentreconciliationsim.storage.SettlementStorage settlementStorage() {
        return context.getBean(org.octavio.paymentreconciliationsim.storage.SettlementStorage.class);
    }
    public Clock clock() { return context.getBean(Clock.class); }

    private void enqueueWorker(java.util.Map<String,Object> event) {
        if (closed) throw new IllegalStateException("The local environment is already closed");
        workerInvocations.add(workerExecutor.submit(() -> invokeWorker(event)));
    }

    public void invokeWorker(java.util.Map<String,Object> event) { workerHandler.handleRequest(event, null); }

    /** Upload callbacks are asynchronous; drain before reset/close to prohibit cross-scenario writes. */
    public void awaitWorker() {
        java.util.concurrent.Future<?> invocation;
        while ((invocation = workerInvocations.peek()) != null) {
            try {
                invocation.get(WORKER_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                workerInvocations.remove(invocation);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Local worker wait interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException failure) {
                // Exceptional completion also proves the callable has exited.
                workerInvocations.remove(invocation);
                throw new IllegalStateException("Local worker invocation failed", failure);
            } catch (java.util.concurrent.TimeoutException failure) {
                // Retain the unfinished future: a later reset must wait for it again.
                throw new IllegalStateException("Local worker invocation timed out", failure);
            } catch (java.util.concurrent.CancellationException cancelled) {
                // A cancelled Future can be done while its executing callable still runs.
                throw new IllegalStateException("Local worker cancellation has unconfirmed termination", cancelled);
            }
        }
    }

    /** Preserve startup-created indexes while removing every scenario's database and S3 data. */
    public void resetScenario() {
        if (closed) throw new IllegalStateException("The local environment is already closed");
        awaitWorker();
        for (String collection : mongoDatabase().listCollectionNames()) {
            mongoDatabase().getCollection(collection).deleteMany(new Document());
        }
        s3.reset();
    }

    boolean mongoRunning() { return mongo.isRunning(); }
    boolean springRunning() { return context != null && context.isActive(); }

    @Override
    public synchronized void close() {
        if (resourcesClosed) return;
        closed = true;
        RuntimeException failure = null;
        try { awaitWorker(); } catch (RuntimeException e) { failure = e; }
        workerExecutor.shutdownNow();
        // Never release shared resources on Future cancellation alone. If this wait fails,
        // the environment stays unusable, and a later close can finish actual cleanup.
        boolean restoreInterrupt = Thread.interrupted();
        try {
            if (!workerExecutor.awaitTermination(WORKER_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                var notStopped = new IllegalStateException("Local worker did not terminate; environment remains closed");
                if (failure != null) notStopped.addSuppressed(failure);
                throw notStopped;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var notStopped = new IllegalStateException("Local worker termination wait interrupted; environment remains closed", interrupted);
            if (failure != null) notStopped.addSuppressed(failure);
            throw notStopped;
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        resourcesClosed = true;
        if (workerHttp != null) {
            try { workerHttp.close(); } catch (RuntimeException e) { failure = append(failure, e); }
        }
        if (workerS3 != null) {
            try { workerS3.close(); } catch (RuntimeException e) { failure = append(failure, e); }
        }
        if (context != null) {
            try { context.close(); } catch (RuntimeException e) { failure = append(failure, e); }
        }
        if (mongoClient != null) {
            try { mongoClient.close(); } catch (RuntimeException e) { failure = append(failure, e); }
        }
        try { s3.close(); } catch (RuntimeException e) { failure = append(failure, e); }
        try { mongo.stop(); } catch (RuntimeException e) { failure = append(failure, e); }
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException shuttingDown) { /* The registered hook still closes idempotently. */ }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException append(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    @Configuration(proxyBeanMethods = false)
    public static class FixedClockConfiguration {
        @Bean("acceptanceClock")
        @Primary
        Clock acceptanceClock() { return Clock.fixed(Instant.parse("2026-10-02T15:00:00Z"), ZoneOffset.UTC); }

        @Bean
        @ConditionalOnProperty(name = "acceptance.fail-startup", havingValue = "true")
        ApplicationRunner startupFailureProbe() {
            return arguments -> { throw new IllegalStateException("Deliberate acceptance startup failure after HTTP initialization"); };
        }
    }
}
