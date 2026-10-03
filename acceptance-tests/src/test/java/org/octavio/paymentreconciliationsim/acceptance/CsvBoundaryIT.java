package org.octavio.paymentreconciliationsim.acceptance;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary evidence crosses real HTTP, stored versions, handler and Mongo publication. */
class CsvBoundaryIT {
    static LocalEnvironment environment;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String HEADER = "business_date,transaction_reference,amount_centavos,currency\n";
    @BeforeAll static void start() { environment = new LocalEnvironment(); environment.start(); }
    @AfterAll static void stop() { if (environment != null) environment.close(); }
    @BeforeEach void reset() throws Exception {
        environment.resetScenario();
        assertEquals(201, request("POST", "/business-dates", "{\"businessDate\":\"2026-10-01\"}").statusCode());
        assertEquals(200, request("POST", "/business-dates/2026-10-01/close", "{}").statusCode());
    }
    static HttpResponse<String> request(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(environment.apiBaseUri().resolve("/api/v1" + path))
                    .header("Authorization", "Bearer " + LocalEnvironment.DEMO_TOKEN).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    static HttpResponse<String> register(byte[] bytes) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return request("POST", "/reconciliation-runs", "{\"businessDate\":\"2026-10-01\",\"sha256\":\"" + hash + "\",\"byteLength\":" + bytes.length + "}");
    }
    static JsonNode upload(String csv) throws Exception {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        var response = register(bytes);
        assertEquals(201, response.statusCode(), response.body());
        var registration = JSON.readTree(response.body());
        try (var client = HttpClient.newHttpClient()) {
            var uploaded = client.send(HttpRequest.newBuilder(URI.create(registration.path("uploadInstructions").path("url").asText()))
                    .header("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, uploaded.statusCode(), uploaded.body());
        }
        environment.awaitWorker();
        return JSON.readTree(request("GET", "/reconciliation-runs/" + registration.path("runId").asText(), null).body());
    }
    @Test void exactlyTwoThousandRowsPublishCompleteResultsAndOneExtraFailsWithoutPartialOutput() throws Exception {
        var valid = upload(HEADER + java.util.stream.IntStream.range(0, 2000)
                .mapToObj(index -> "2026-10-01,R_" + index + ",1,ARS\n").collect(java.util.stream.Collectors.joining()));
        assertEquals("COMPLETED", valid.path("status").asText());
        assertEquals(2000, valid.path("summary").path("settlementRowCount").asInt());
        assertEquals(2000, valid.path("summary").path("totalResultCount").asInt());
        var page = JSON.readTree(request("GET", "/reconciliation-runs/" + valid.path("runId").asText() + "/results?size=100", null).body());
        assertEquals(2000, page.path("totalResults").asInt());
        assertEquals(100, page.path("results").size());
        var invalid = upload(HEADER + "2026-10-01,A,1,ARS\n".repeat(2001));
        assertEquals("FAILED", invalid.path("status").asText());
        assertEquals("TOO_MANY_ROWS", invalid.path("error").path("code").asText());
        assertTrue(invalid.path("summary").isNull());
        assertEquals(409, request("GET", "/reconciliation-runs/" + invalid.path("runId").asText() + "/results", null).statusCode());
    }
    @Test void exactlyTwoMiBPublishAndOneAdditionalByteCannotRegister() throws Exception {
        String prefix = HEADER + "2026-10-01,A,1,\"ARS\"";
        String csv = prefix + " ".repeat(2097152 - prefix.length() - 1) + "\n";
        assertEquals(2097152, csv.getBytes(StandardCharsets.UTF_8).length);
        var valid = upload(csv);
        assertEquals("COMPLETED", valid.path("status").asText());
        assertEquals(1, valid.path("summary").path("settlementRowCount").asInt());
        var page = JSON.readTree(request("GET", "/reconciliation-runs/" + valid.path("runId").asText() + "/results", null).body());
        assertEquals("MISSING_INTERNALLY", page.path("results").get(0).path("outcome").asText());
        assertEquals(1, page.path("results").get(0).path("settlementEvidence").get(0).path("amountCentavos").asLong());
        var rejected = register((csv + " ").getBytes(StandardCharsets.UTF_8));
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertEquals("INVALID_REQUEST", JSON.readTree(rejected.body()).path("code").asText());
        assertEquals(1, environment.mongoDatabase().getCollection("reconciliation_runs").countDocuments());
    }
}
