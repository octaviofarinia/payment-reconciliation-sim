package org.octavio.paymentreconciliationsim.config;

import java.net.URI;
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
            assertEquals("https://test-bucket.s3.amazonaws.com/test.csv",
                    client.utilities().getUrl(b -> b.bucket("test-bucket").key("test.csv")).toString());
            assertNotNull(presigner);
        }
        ((DefaultCredentialsProvider) credentials).close();
    }
}
