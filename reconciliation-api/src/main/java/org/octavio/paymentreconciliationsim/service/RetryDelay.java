package org.octavio.paymentreconciliationsim.service;

@FunctionalInterface
public interface RetryDelay {
    void pause(int retryNumber);
}
