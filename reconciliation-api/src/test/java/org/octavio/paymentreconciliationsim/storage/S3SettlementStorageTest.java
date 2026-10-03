package org.octavio.paymentreconciliationsim.storage;

import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.run.RunContracts.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3SettlementStorageTest {
    static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");
    static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static final String BASE64 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=";
    static final String KEY = "settlements/2026-10-01/server-id.csv";
    final S3Client client = mock(S3Client.class);
    S3Presigner presigner;
    S3SettlementStorage storage;

    @BeforeEach void setup() {
        presigner = S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("acceptance", "acceptance")))
                .endpointOverride(URI.create("http://localhost:9000"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        storage = new S3SettlementStorage(client, presigner, "bucket");
    }
    @AfterEach void close() { presigner.close(); }

    @Test void uploadChecksumAndBoundVersionAreStable() {
        var instructions = storage.upload(KEY, HASH, 3);
        assertEquals(BASE64, instructions.requiredHeaders().get("x-amz-checksum-sha256"));
        assertEquals("3", instructions.requiredHeaders().get("content-length"));
        String signedAt = instructions.url().split("X-Amz-Date=")[1].split("&")[0];
        var signedInstant = java.time.LocalDateTime.parse(signedAt,
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);
        assertEquals(signedInstant.plusSeconds(600), instructions.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertTrue(instructions.url().startsWith("http://localhost:9000/bucket/" + KEY + "?"));
        assertTrue(instructions.url().contains("X-Amz-Expires=600"));
        assertTrue(instructions.url().contains("X-Amz-SignedHeaders=content-length%3Bhost%3Bx-amz-checksum-sha256"));
        assertTrue(instructions.url().contains("X-Amz-Credential=acceptance%2F"));
        assertThrows(UnsupportedOperationException.class, () -> instructions.requiredHeaders().put("other", "value"));
    }

    @Test void rejectsInvalidUploadBoundsAndChecksumBeforeSigning() {
        for (long length : new long[]{0, -1, 2097153, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> storage.upload(KEY, HASH, length));
        }
        for (String hash : Arrays.asList(null, "", "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> storage.upload(KEY, hash, 1));
        }
        assertNotNull(storage.upload(KEY, HASH, 1));
        assertEquals("2097152", storage.upload(KEY, HASH, 2097152).requiredHeaders().get("content-length"));
    }

    @Test void recoveryCannotChangeTheBucketOfAnExistingBinding() {
        var foreign = new ObjectIdentity("foreign", KEY, "first", HASH);
        head("first", 3, BASE64); body("first", new byte[]{97, 98, 99});
        assertTrue(storage.recoverUploadedVersion(run(foreign)).isEmpty());
        verifyNoInteractions(client);
    }

    @Test void recoveryChecksOnlyBoundVersionEvenWhenOtherRetainedBytesMatch() {
        var bound = new ObjectIdentity("bucket", KEY, "first", HASH);
        head("first", 3, BASE64);
        body("first", new byte[]{97, 98, 99});
        assertEquals(Optional.of(bound), storage.recoverUploadedVersion(run(bound)));
        verifyNoMoreInteractionsExceptBound("first");
        body("first", new byte[]{97, 98, 100});
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        verify(client, never()).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @Test void lostEventRecoveryFindsEarliestMatchingRetainedVersionAcrossPages() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(call -> {
            ListObjectVersionsRequest request = call.getArgument(0);
            assertEquals("bucket", request.bucket());
            assertEquals(KEY, request.prefix());
            if (request.keyMarker() == null) {
                return ListObjectVersionsResponse.builder().isTruncated(true).nextKeyMarker(KEY).nextVersionIdMarker("wrong")
                        .versions(version(KEY, "wrong", NOW), version(KEY + ".other", "irrelevant", NOW),
                                version(KEY, "later", NOW.plusSeconds(1))).build();
            }
            assertEquals(KEY, request.keyMarker());
            assertEquals("wrong", request.versionIdMarker());
            return ListObjectVersionsResponse.builder().isTruncated(false)
                    .versions(version(KEY, "first", NOW.minusSeconds(1))).build();
        });
        head("first", 3, BASE64); body("first", new byte[]{97, 98, 99});
        head("later", 3, BASE64); body("later", new byte[]{97, 98, 99});
        head("wrong", 3, null); body("wrong", new byte[]{97, 98, 100});
        assertEquals(new ObjectIdentity("bucket", KEY, "first", HASH), storage.recoverUploadedVersion(run(null)).orElseThrow());
        verify(client, never()).headObject(HeadObjectRequest.builder().bucket("bucket").key(KEY).versionId("irrelevant").checksumMode(ChecksumMode.ENABLED).build());
    }

    @Test void recoveryRejectsWrongSizeChecksumAndRawBytesWithoutUsingEtag() {
        var bound = new ObjectIdentity("bucket", KEY, "first", HASH);
        head("first", 2, BASE64);
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        head("first", 3, "wrong");
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        head("first", 3, null);
        body("first", new byte[]{97, 98, 100});
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        body("first", new byte[]{97, 98});
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        body("first", new byte[]{97, 98, 99, 100});
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        body("first", new byte[]{97, 98, 99});
        assertEquals(bound, storage.recoverUploadedVersion(run(bound)).orElseThrow());
    }

    @Test void recoveryReturnsEmptyForNoVersionsOrDeletedVersionAndPropagatesTransportFailures() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().isTruncated(false).build());
        assertTrue(storage.recoverUploadedVersion(run(null)).isEmpty());
        var bound = new ObjectIdentity("bucket", KEY, "first", HASH);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertTrue(storage.recoverUploadedVersion(run(bound)).isEmpty());
        var denied = S3Exception.builder().statusCode(403).build();
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(denied);
        assertSame(denied, assertThrows(S3Exception.class, () -> storage.recoverUploadedVersion(run(bound))));
        head("first", 3, BASE64);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("broken connection"); }
                })));
        assertEquals("broken connection", assertThrows(UncheckedIOException.class,
                () -> storage.recoverUploadedVersion(run(bound))).getCause().getMessage());
    }

    @Test void recoveryNeverBindsPrefixMatchesFromOtherKeys() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().isTruncated(false)
                        .versions(version(KEY + ".other", "irrelevant", NOW)).build());
        assertTrue(storage.recoverUploadedVersion(run(null)).isEmpty());
        verify(client, never()).headObject(any(HeadObjectRequest.class));
        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test void recoverySkipsMismatchedCandidateAndKeepsSearching() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().isTruncated(false)
                        .versions(version(KEY, "wrong", NOW), version(KEY, "later", NOW.plusSeconds(1))).build());
        head("wrong", 3, null); body("wrong", new byte[]{97, 98, 100});
        head("later", 3, BASE64); body("later", new byte[]{97, 98, 99});
        assertEquals("later", storage.recoverUploadedVersion(run(null)).orElseThrow().versionId());
        head("later", 2, BASE64);
        assertTrue(storage.recoverUploadedVersion(run(null)).isEmpty());
    }

    private void verifyNoMoreInteractionsExceptBound(String version) {
        verify(client).headObject(HeadObjectRequest.builder().bucket("bucket").key(KEY).versionId(version).checksumMode(ChecksumMode.ENABLED).build());
        verify(client).getObject(GetObjectRequest.builder().bucket("bucket").key(KEY).versionId(version).checksumMode(ChecksumMode.ENABLED).build());
        verifyNoMoreInteractions(client);
    }
    void head(String version, long size, String checksum) {
        doReturn(HeadObjectResponse.builder().contentLength(size).checksumSHA256(checksum).eTag(HASH).build())
                .when(client).headObject(HeadObjectRequest.builder().bucket("bucket").key(KEY).versionId(version).checksumMode(ChecksumMode.ENABLED).build());
    }
    void body(String version, byte[] bytes) {
        when(client.getObject(GetObjectRequest.builder().bucket("bucket").key(KEY).versionId(version).checksumMode(ChecksumMode.ENABLED).build()))
                .thenAnswer(call -> new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(new ByteArrayInputStream(bytes))));
    }
    static ObjectVersion version(String key, String id, Instant instant) {
        return ObjectVersion.builder().key(key).versionId(id).lastModified(instant).build();
    }
    static RunMetadata run(ObjectIdentity object) {
        return new RunMetadata(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), "SIMULATED", "2026-10-01",
                HASH, "v1", 3, KEY, Status.AWAITING_UPLOAD, object, null, null, NOW, NOW, false);
    }
}
