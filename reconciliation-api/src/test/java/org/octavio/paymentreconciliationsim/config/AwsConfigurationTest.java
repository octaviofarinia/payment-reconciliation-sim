package org.octavio.paymentreconciliationsim.config;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.services.s3.S3Client;
import static org.mockito.Mockito.mockStatic;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.auth.credentials.*;
import static org.junit.jupiter.api.Assertions.*;

class AwsConfigurationTest {
    final AwsConfiguration config = new AwsConfiguration();
    @Test void localClientsUseExplicitDummyCredentialsAndPathStyleEndpoint() {
        var environment = new MockEnvironment().withProperty("reconciliation.s3.endpoint", "http://localhost:9000")
                .withProperty("reconciliation.aws.region", "us-east-1")
                .withProperty("reconciliation.aws.access-key", "acceptance")
                .withProperty("reconciliation.aws.secret-key", "acceptance");
        var credentials = config.awsCredentials(environment);
        assertInstanceOf(StaticCredentialsProvider.class, credentials);
        assertEquals("acceptance", credentials.resolveCredentials().accessKeyId());
        assertEquals("acceptance", credentials.resolveCredentials().secretAccessKey());
        try (var client = config.s3Client(environment, credentials); var presigner = config.s3Presigner(environment, credentials)) {
            assertEquals(URI.create("http://localhost:9000"), client.serviceClientConfiguration().endpointOverride().orElseThrow());
            var signed = presigner.presignPutObject(b -> b.signatureDuration(java.time.Duration.ofMinutes(10))
                    .putObjectRequest(r -> r.bucket("bucket").key("test.csv")));
            assertTrue(signed.url().toString().startsWith("http://localhost:9000/bucket/test.csv?"));
            assertNotNull(presigner);
        }
        environment = new MockEnvironment().withProperty("reconciliation.s3.endpoint", "http://localhost:9000");
        assertEquals("local", config.awsCredentials(environment).resolveCredentials().accessKeyId());
    }
    @Test void cloudClientsUseRoleCredentialChainWithoutResolvingHostCredentials() {
        var environment = new MockEnvironment().withProperty("reconciliation.aws.region", "us-east-1");
        var credentials = config.awsCredentials(environment);
        assertInstanceOf(DefaultCredentialsProvider.class, credentials);
        try (var client = config.s3Client(environment, credentials); var presigner = config.s3Presigner(environment, credentials)) {
            assertTrue(client.serviceClientConfiguration().endpointOverride().isEmpty());
            assertNotNull(presigner);
        }
        ((DefaultCredentialsProvider) credentials).close();
    }

    @Test void clientsUseConfiguredTransportHostAndPathWithDummyCredentials() {
        var request = new AtomicReference<SdkHttpRequest>();
        SdkHttpClient offlineTransport = new SdkHttpClient() {
            @Override public ExecutableHttpRequest prepareRequest(HttpExecuteRequest execution) {
                request.set(execution.httpRequest());
                return new ExecutableHttpRequest() {
                    @Override public HttpExecuteResponse call() {
                        return HttpExecuteResponse.builder()
                                .response(SdkHttpResponse.builder().statusCode(200)
                                        .putHeader("content-length", "0").build()).build();
                    }
                    @Override public void abort() {}
                };
            }
            @Override public void close() {}
        };
        var dummyCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"));
        for (String endpoint : new String[] {null, "http://storage.example.test:9000"}) {
            var environment = new MockEnvironment().withProperty("reconciliation.aws.region", "eu-west-1");
            if (endpoint != null) environment.withProperty("reconciliation.s3.endpoint", endpoint);
            request.set(null);
            // Replace only the builder's HTTP transport. The production factory
            // still applies its actual endpoint and path-style configuration.
            var builder = S3Client.builder().httpClient(offlineTransport);
            try (var factory = mockStatic(S3Client.class)) {
                factory.when(S3Client::builder).thenReturn(builder);
                try (var client = config.s3Client(environment, dummyCredentials)) {
                    client.headObject(b -> b.bucket("test-bucket").key("nested/test.csv"));
                }
            }
            assertNotNull(request.get());
            assertEquals(SdkHttpMethod.HEAD, request.get().method());
            assertEquals(endpoint == null ? "https" : "http", request.get().protocol());
            assertEquals(endpoint == null ? "test-bucket.s3.eu-west-1.amazonaws.com" : "storage.example.test",
                    request.get().host());
            assertEquals(endpoint == null ? "/nested/test.csv" : "/test-bucket/nested/test.csv",
                    request.get().encodedPath());
        }
    }
}
