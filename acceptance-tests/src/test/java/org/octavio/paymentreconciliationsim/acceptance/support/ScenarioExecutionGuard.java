package org.octavio.paymentreconciliationsim.acceptance.support;

import java.util.concurrent.atomic.AtomicInteger;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;

/** All-filtered or empty feature selections must not claim an acceptance verification. */
public final class ScenarioExecutionGuard implements ConcurrentEventListener {
    private final AtomicInteger executed = new AtomicInteger();
    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, event -> executed.incrementAndGet());
        publisher.registerHandlerFor(TestRunFinished.class, event -> {
            if (executed.get() == 0) throw new IllegalStateException("No acceptance scenarios executed; check feature discovery and filters");
        });
    }
}
