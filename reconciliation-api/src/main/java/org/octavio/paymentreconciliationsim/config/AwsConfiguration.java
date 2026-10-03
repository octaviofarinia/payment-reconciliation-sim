package org.octavio.paymentreconciliationsim.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class AwsConfiguration {
    @Bean
    public AwsCredentialsProvider awsCredentials(Environment environment) {
        if (environment.containsProperty("reconciliation.s3.endpoint")) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    environment.getProperty("reconciliation.aws.access-key", "local"),
                    environment.getProperty("reconciliation.aws.secret-key", "local")));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client(Environment environment, AwsCredentialsProvider credentials) {
        String endpoint = environment.getProperty("reconciliation.s3.endpoint");
        var builder = S3Client.builder().region(Region.of(environment.getRequiredProperty("reconciliation.aws.region")))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(endpoint != null).build());
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(Environment environment, AwsCredentialsProvider credentials) {
        String endpoint = environment.getProperty("reconciliation.s3.endpoint");
        var builder = S3Presigner.builder().region(Region.of(environment.getRequiredProperty("reconciliation.aws.region")))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(endpoint != null).build());
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
