package org.octavio.paymentreconciliationsim.config;

import java.time.Duration;
import org.octavio.paymentreconciliationsim.service.RetryDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Configuration(proxyBeanMethods = false)
public class MongoConfiguration {
    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }

    @Bean
    public TransactionTemplate transactionTemplate(MongoTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    public RetryDelay retryDelay() {
        return retry -> {
            try {
                Thread.sleep(Duration.ofMillis(10L * retry));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted concurrent update", interrupted);
            }
        };
    }
}
