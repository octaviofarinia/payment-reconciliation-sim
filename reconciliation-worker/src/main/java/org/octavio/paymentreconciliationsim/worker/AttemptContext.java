package org.octavio.paymentreconciliationsim.worker;
import java.time.Instant;
public record AttemptContext(String attemptId, Instant startedAt) {}
