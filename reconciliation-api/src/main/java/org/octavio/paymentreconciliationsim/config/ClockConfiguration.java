package org.octavio.paymentreconciliationsim.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    @Bean
    public Clock applicationClock() {
        return Clock.system(ZoneId.of("America/Buenos_Aires"));
    }
}
