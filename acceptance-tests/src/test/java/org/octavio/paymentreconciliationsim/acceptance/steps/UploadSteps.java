package org.octavio.paymentreconciliationsim.acceptance.steps;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import io.cucumber.java.en.*;
import org.octavio.paymentreconciliationsim.acceptance.support.*;
import org.octavio.paymentreconciliationsim.run.RunContracts.*;
import org.octavio.paymentreconciliationsim.storage.SettlementStorage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

public class UploadSteps {
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String CHECKSUM = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=";
    private final ScenarioWorld world;
    private final JsonMapper json = JsonMapper.builder().build();
    private JsonNode registration;
    private String first;
    private String second;

    public UploadSteps(ScenarioWorld world) { this.world = world; }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve(path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (path.startsWith("/internal/")?LocalEnvironment.WORKER_TOKEN:LocalEnvironment.DEMO_TOKEN))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @When("I register the three-byte settlement input")
    public void register() throws Exception {
        var response = request("POST", "/api/v1/reconciliation-runs",
                "{\"businessDate\":\"2026-10-01\",\"sha256\":\"" + HASH + "\",\"byteLength\":3}");
        assertEquals(201, response.statusCode());
        registration = json.readTree(response.body());
        world.runId = registration.get("runId").stringValue();
    }

    @Then("the upload instructions declare the exact checksum and bounded expiry")
    public void instructions() {
        var instructions = registration.get("uploadInstructions");
        assertEquals(CHECKSUM, instructions.get("requiredHeaders").get("x-amz-checksum-sha256").stringValue());
        assertEquals("3", instructions.get("requiredHeaders").get("content-length").stringValue());
        String url = instructions.get("url").stringValue();
        assertTrue(url.startsWith(world.environment.s3Endpoint() + "/" + LocalEnvironment.BUCKET + "/"
                + registration.get("objectKey").stringValue() + "?"));
        assertTrue(url.contains("X-Amz-Expires=600"));
        var expiresAt = Instant.parse(instructions.get("expiresAt").stringValue());
        assertTrue(expiresAt.isAfter(Instant.now()));
        assertTrue(expiresAt.isBefore(Instant.now().plusSeconds(601)));
        assertEquals(1, world.environment.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
    }

    private HttpResponse<String> upload(byte[] bytes) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create(registration.get("uploadInstructions").get("url").stringValue()))
                    .header("x-amz-checksum-sha256", CHECKSUM).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @When("I upload registered bytes twice and reject altered bytes")
    public void versions() throws Exception {
        var one = upload(new byte[]{97, 98, 99});
        var two = upload(new byte[]{97, 98, 99});
        assertEquals(200, one.statusCode());
        assertEquals(200, two.statusCode());
        first = one.headers().firstValue("x-amz-version-id").orElseThrow();
        second = two.headers().firstValue("x-amz-version-id").orElseThrow();
        assertNotEquals(first, second);
        assertEquals(400, upload(new byte[]{97, 98, 100}).statusCode());
        // Simulate retained altered bytes supplied outside the constrained upload instructions.
        world.environment.s3().store(LocalEnvironment.BUCKET, registration.get("objectKey").stringValue(), new byte[]{97, 98, 100});
    }

    private RunMetadata metadata() throws Exception {
        return json.readValue(request("GET", "/internal/v1/reconciliation-runs/" + world.runId, null).body(), RunMetadata.class);
    }

    @Then("uploadChecksumAndBoundVersionAreStable")
    public void stable() throws Exception {
        SettlementStorage storage = world.environment.settlementStorage();
        var recovered = storage.recoverUploadedVersion(metadata()).orElseThrow();
        assertEquals(first, recovered.versionId());
        var path = "/internal/v1/reconciliation-runs/" + world.runId + "/processing";
        assertEquals(200, request("PUT", path, json.writeValueAsString(recovered)).statusCode());
        var later = new ObjectIdentity(LocalEnvironment.BUCKET, recovered.key(), second, HASH);
        assertEquals(409, request("PUT", path, json.writeValueAsString(later)).statusCode());
        assertEquals(first, metadata().objectIdentity().versionId());
        var input = json.readValue(request("GET", "/internal/v1/reconciliation-runs/" + world.runId + "/input", null).body(), RunInput.class);
        assertEquals(first, input.objectIdentity().versionId());
        assertEquals(recovered, storage.recoverUploadedVersion(metadata()).orElseThrow());
        assertArrayEquals(new byte[]{97, 98, 99}, world.environment.s3().get(LocalEnvironment.BUCKET, recovered.key(), input.objectIdentity().versionId()));
    }

    @Then("no matching retained version is recoverable")
    public void missing() throws Exception {
        assertTrue(world.environment.settlementStorage().recoverUploadedVersion(metadata()).isEmpty());
        world.environment.s3().store(LocalEnvironment.BUCKET, registration.get("objectKey").stringValue(), new byte[]{97, 98, 100});
        assertTrue(world.environment.settlementStorage().recoverUploadedVersion(metadata()).isEmpty());
        assertNull(metadata().objectIdentity());
    }
}
