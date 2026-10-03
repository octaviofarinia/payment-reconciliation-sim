package org.octavio.paymentreconciliationsim.acceptance.support;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/** A fresh instance is constructor-injected by PicoContainer for each scenario. */
public final class ScenarioWorld {
    public LocalEnvironment environment;
    public HttpResponse<byte[]> response;
    public String runId;
    public final Map<String, byte[]> fixtures = new HashMap<>();
}
