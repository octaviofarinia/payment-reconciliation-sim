package org.octavio.paymentreconciliationsim.acceptance.support;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import static org.junit.jupiter.api.Assertions.*;

class S3StubIT {
    @Test
    void retainsBinaryVersionsAndReturnsSdkChecksumHeaders() throws Exception {
        try (var stub = new S3Stub()) {
            stub.start();
            try (var client = S3Client.builder().endpointOverride(stub.endpoint()).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("acceptance", "acceptance")))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build()).build()) {
                byte[] first = {0, (byte) 0xff, 1, 13, 10};
                var put = client.putObject(b -> b.bucket("bucket").key("a+b/c %2F.csv"), RequestBody.fromBytes(first));
                assertNotNull(put.versionId());
                assertEquals("eLYWxbm2s8crgt/rM6QEuXBcZAwid+jKFQttcL2USdQ=", put.checksumSHA256());
                byte[] second = {42, 0, (byte) 0xc0};
                var next = client.putObject(b -> b.bucket("bucket").key("a+b/c %2F.csv"), RequestBody.fromBytes(second));
                assertNotEquals(put.versionId(), next.versionId());
                var head = client.headObject(b -> b.bucket("bucket").key("a+b/c %2F.csv").versionId(put.versionId()).checksumMode(ChecksumMode.ENABLED));
                assertEquals(5L, head.contentLength());
                assertEquals(put.versionId(), head.versionId());
                assertEquals(put.checksumSHA256(), head.checksumSHA256());
                assertArrayEquals(first, client.getObjectAsBytes(b -> b.bucket("bucket").key("a+b/c %2F.csv").versionId(put.versionId()).checksumMode(ChecksumMode.ENABLED)).asByteArray());
                assertArrayEquals(second, stub.get("bucket", "a+b/c %2F.csv", null));
                byte[] retrieved = stub.get("bucket", "a+b/c %2F.csv", put.versionId());
                retrieved[0] = 100;
                assertArrayEquals(first, stub.get("bucket", "a+b/c %2F.csv", put.versionId()));
                String stored = stub.store("bucket", "direct.csv", second);
                second[0] = 99;
                assertArrayEquals(new byte[]{42, 0, (byte)0xc0}, client.getObjectAsBytes(b -> b.bucket("bucket").key("direct.csv").versionId(stored)).asByteArray());
            }
        }
    }

    @Test
    void acceptsDefaultSdkStreamingChecksumsWithoutChangingPayload() {
        try (var stub = new S3Stub()) {
            stub.start();
            try (var client = S3Client.builder().endpointOverride(stub.endpoint()).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("acceptance", "acceptance")))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                byte[] payload = {0, (byte) 0xff, 13, 10, 42};
                var put = client.putObject(b -> b.bucket("bucket").key("streamed.csv"), RequestBody.fromBytes(payload));
                assertArrayEquals(payload, stub.get("bucket", "streamed.csv", put.versionId()));
                assertArrayEquals(payload, client.getObjectAsBytes(b -> b.bucket("bucket").key("streamed.csv").versionId(put.versionId())).asByteArray());
            }
        }
    }

    @Test
    void missingObjectAndMissingVersionReturn404AndHeadHasNoBody() throws Exception {
        try (var stub = new S3Stub(); var client = HttpClient.newHttpClient()) {
            stub.start();
            String version = stub.store("bucket", "key", new byte[]{1, 2});
            var head = client.send(HttpRequest.newBuilder(stub.endpoint().resolve("/bucket/key?versionId=" + version)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals("2", head.headers().firstValue("Content-Length").orElseThrow());
            assertEquals(404, client.send(HttpRequest.newBuilder(stub.endpoint().resolve("/bucket/missing")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(stub.endpoint().resolve("/bucket/key?versionId=missing")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
