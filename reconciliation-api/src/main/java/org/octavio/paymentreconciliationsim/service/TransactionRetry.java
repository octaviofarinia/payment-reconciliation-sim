package org.octavio.paymentreconciliationsim.service;

import java.util.function.Supplier;
import com.mongodb.MongoException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TransactionRetry {
    private final RetryDelay delay;

    public TransactionRetry(RetryDelay delay) {
        this.delay = delay;
    }

    public <T> T execute(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                if (!retriable(failure)) {
                    throw failure;
                }
                if (attempt == 3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent update; retry request", failure);
                }
                delay.pause(attempt);
            }
        }
    }

    private boolean retriable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException || cause instanceof TransientDataAccessException) {
                return true;
            }
            if (cause instanceof MongoException mongo
                    && (mongo.hasErrorLabel("TransientTransactionError") || mongo.getCode() == 112)) {
                return true;
            }
        }
        return false;
    }
}
