package org.octavio.paymentreconciliationsim.acceptance.steps;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import io.cucumber.java.Before;
import io.cucumber.java.After;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.AfterAll;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import org.bson.Document;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import org.octavio.paymentreconciliationsim.acceptance.support.MongoTransactionProbe;
import org.octavio.paymentreconciliationsim.acceptance.support.ScenarioWorld;
import static org.junit.jupiter.api.Assertions.*;

public final class EnvironmentSteps {
    private static LocalEnvironment suiteEnvironment;
    private final ScenarioWorld world;
    public EnvironmentSteps(ScenarioWorld world) { this.world = world; }

    public static LocalEnvironment suiteEnvironment() { return suiteEnvironment; }

    @BeforeAll
    public static void startSuite() {
        suiteEnvironment = new LocalEnvironment();
        suiteEnvironment.start();
    }
    @AfterAll
    public static void closeSuite() {
        if (suiteEnvironment != null) suiteEnvironment.close();
    }
    @Before(order = 0)
    public void isolateScenario() {
        suiteEnvironment.resetScenario();
        world.environment = suiteEnvironment;
    }
    @After(order = 0)
    public void clearScenario() { suiteEnvironment.resetScenario(); }

    @Given("the local reconciliation environment is running")
    public void environmentRunning() { assertNotNull(world.environment.apiBaseUri()); }

    @Given("the scenario state and persistent fixtures are fresh")
    public void scenarioFresh() {
        assertNull(world.response);
        assertNull(world.runId);
        assertTrue(world.fixtures.isEmpty());
        assertEquals(0, world.environment.mongoDatabase().getCollection("isolation_probe").countDocuments());
        assertNull(world.environment.s3().get(LocalEnvironment.BUCKET, "isolation_probe", null));
    }
    @When("I retain scenario fixtures")
    public void retainFixtures() {
        world.runId = "scenario-probe";
        world.fixtures.put("probe", new byte[]{1});
        world.environment.mongoDatabase().getCollection("isolation_probe").insertOne(new Document("_id", "probe"));
        world.environment.s3().store(LocalEnvironment.BUCKET, "isolation_probe", new byte[]{1});
    }

    @When("I request an unknown API path with local credentials")
    public void requestUnknownPath() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            world.response = client.send(HttpRequest.newBuilder(world.environment.apiBaseUri().resolve("/unknown-acceptance-path"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + LocalEnvironment.DEMO_TOKEN)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
    @Then("the HTTP status is {int}")
    public void httpStatus(int expected) { assertEquals(expected, world.response.statusCode()); }

    @Then("Mongo supports committing and rolling back transactions")
    public void mongoTransactions() {
        var database = world.environment.mongoDatabase();
        assertNotNull(database.runCommand(new Document("hello", 1)).getString("setName"));
        var collection = database.getCollection("transaction_probe");
        MongoTransactionProbe.assertCommitAndRollback(world.environment.mongoClient(), collection);
    }
}
