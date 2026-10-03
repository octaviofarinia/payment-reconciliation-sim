package org.octavio.paymentreconciliationsim.acceptance.support;

import io.cucumber.core.cli.Main;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.acceptance.steps.EnvironmentSteps;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioFailureIT {
    @Test
    void deliberatelyFailedScenarioClosesTheSuiteEnvironment() throws Exception {
        byte exit = Main.run(new String[]{"--glue", "org.octavio.paymentreconciliationsim.acceptance.steps",
                "--plugin", "summary", "classpath:fixtures/failing-environment.feature"}, getClass().getClassLoader());
        assertEquals(1, exit, "A deliberate scenario failure must be reported as failure");
        var environment = EnvironmentSteps.suiteEnvironment();
        LocalEnvironmentIT.assertStopped(environment, environment.apiBaseUri(), environment.s3Endpoint());
    }
}
