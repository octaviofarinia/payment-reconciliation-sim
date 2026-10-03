package org.octavio.paymentreconciliationsim.acceptance.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalEnvironmentIT {
    @Test
    void localEnvironmentUsesRealHttpAndReplicaSet() throws Exception {
        var environment = new LocalEnvironment();
        URI api;
        URI s3;
        try (environment; var client = HttpClient.newHttpClient()) {
            environment.start();
            api = environment.apiBaseUri();
            s3 = environment.s3Endpoint();
            var unknownPathResponse = client.send(HttpRequest.newBuilder(api.resolve("/unknown-local-path"))
                    .header("Authorization", "Bearer " + LocalEnvironment.DEMO_TOKEN).timeout(Duration.ofSeconds(10))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, unknownPathResponse.statusCode());
            var mongoHelloResponse = environment.mongoDatabase().runCommand(new Document("hello", 1));
            assertNotNull(mongoHelloResponse.getString("setName"));
            assertEquals(Instant.parse("2026-10-02T15:00:00Z"), environment.clock().instant());
            var collection = environment.mongoDatabase().getCollection("transactions");
            MongoTransactionProbe.assertCommitAndRollback(environment.mongoClient(), collection);
            environment.s3().store(LocalEnvironment.BUCKET, "fixture", new byte[]{1});
            environment.resetScenario();
            assertEquals(0, collection.countDocuments());
            assertNull(environment.s3().get(LocalEnvironment.BUCKET, "fixture", null));
        }
        assertStopped(environment, api, s3);
        environment.close(); // Idempotent cleanup must remain safe after success.
    }

    @Test
    void unavailableDockerFailsClearlyWithoutStartingResources() {
        try (var environment = new LocalEnvironment(() -> false)) {
            var failure = assertThrows(IllegalStateException.class, environment::start);
            assertTrue(failure.getMessage().contains("Docker is required"));
            assertFalse(environment.mongoRunning());
            assertFalse(environment.s3().isRunning());
            assertFalse(environment.springRunning());
        }
    }

    @Test
    void startupFailureStopsAlreadyStartedSpringMongoAndStub() throws Exception {
        try (var environment = new LocalEnvironment("--acceptance.fail-startup=true")) {
            assertThrows(RuntimeException.class, environment::start);
            assertNotNull(environment.apiBaseUri(), "Startup probe must fail after the HTTP server opened its port");
            assertStopped(environment, environment.apiBaseUri(), environment.s3Endpoint());
        }
    }

    static void assertStopped(LocalEnvironment environment, URI api, URI s3) throws Exception {
        assertFalse(environment.springRunning());
        assertFalse(environment.s3().isRunning());
        assertFalse(environment.mongoRunning());
        try (var client = HttpClient.newHttpClient()) {
            for (var endpoint : new URI[]{api, s3}) {
                assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()));
            }
        }
    }
}
