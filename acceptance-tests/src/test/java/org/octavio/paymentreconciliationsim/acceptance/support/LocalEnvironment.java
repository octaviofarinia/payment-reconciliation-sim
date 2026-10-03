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
    private boolean closed;

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
            application.addInitializers(initialized -> context = initialized);
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
    public Clock clock() { return context.getBean(Clock.class); }

    /** Preserve startup-created indexes while removing every scenario's database and S3 data. */
    public void resetScenario() {
        for (String collection : mongoDatabase().listCollectionNames()) {
            mongoDatabase().getCollection(collection).deleteMany(new Document());
        }
        s3.reset();
    }

    boolean mongoRunning() { return mongo.isRunning(); }
    boolean springRunning() { return context != null && context.isActive(); }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        if (context != null) {
            try { context.close(); } catch (RuntimeException e) { failure = e; }
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
