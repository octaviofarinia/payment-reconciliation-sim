package org.octavio.paymentreconciliationsim.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.*;
import org.octavio.paymentreconciliationsim.run.RunContracts.ObjectIdentity;
import org.octavio.paymentreconciliationsim.run.RunContracts.RunMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.checksums.DefaultChecksumAlgorithm;
import software.amazon.awssdk.checksums.SdkChecksum;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class S3SettlementStorage implements SettlementStorage {
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3SettlementStorage(S3Client client, S3Presigner presigner,
            @Value("${reconciliation.s3.bucket}") String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public UploadInstructions upload(String objectKey, String sha256, long byteLength) {
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || byteLength < 1 || byteLength > 2097152) {
            throw new IllegalArgumentException("Invalid settlement checksum or byte length");
        }
        String checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
        var put = PutObjectRequest.builder().bucket(bucket).key(objectKey)
                .contentLength(byteLength).checksumSHA256(checksum).build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(600)).putObjectRequest(put).build());
        return new UploadInstructions(signed.url().toString(),
                Map.of("x-amz-checksum-sha256", checksum, "content-length", Long.toString(byteLength)),
                signed.expiration());
    }

    @Override
    public Optional<ObjectIdentity> recoverUploadedVersion(RunMetadata run) {
        if (run.objectIdentity() != null) {
            if (!bucket.equals(run.objectIdentity().bucket())) {
                return Optional.empty();
            }
            return verified(run, run.objectIdentity().versionId());
        }
        var versions = new ArrayList<ObjectVersion>();
        String keyMarker = null;
        String versionMarker = null;
        ListObjectVersionsResponse page;
        do {
            page = client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket)
                    .prefix(run.objectKey()).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            versions.addAll(page.versions());
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        } while (Boolean.TRUE.equals(page.isTruncated()));
        // Choose the oldest retained matching input deterministically after collecting all pages.
        versions.sort(Comparator.comparing(ObjectVersion::lastModified).thenComparing(ObjectVersion::versionId));
        for (var version : versions) {
            if (run.objectKey().equals(version.key())) {
                var identity = verified(run, version.versionId());
                if (identity.isPresent()) {
                    return identity;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ObjectIdentity> verified(RunMetadata run, String version) {
        try {
            var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(run.objectKey())
                    .versionId(version).checksumMode(ChecksumMode.ENABLED).build());
            String expected = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(run.sha256()));
            if (head.contentLength() != run.byteLength()
                    || (head.checksumSHA256() != null && !expected.equals(head.checksumSHA256()))) {
                return Optional.empty();
            }
            byte[] bytes;
            try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(run.objectKey())
                    .versionId(version).checksumMode(ChecksumMode.ENABLED).build())) {
                // Registration bounds byteLength to 2 MiB; one probe detects an oversized response.
                bytes = input.readNBytes(Math.toIntExact(run.byteLength() + 1));
            }
            var checksum = SdkChecksum.forAlgorithm(DefaultChecksumAlgorithm.SHA256);
            checksum.update(bytes);
            if (bytes.length != run.byteLength()
                    || !run.sha256().equals(HexFormat.of().formatHex(checksum.getChecksumBytes()))) {
                return Optional.empty();
            }
            return Optional.of(new ObjectIdentity(bucket, run.objectKey(), version, run.sha256()));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                return Optional.empty();
            }
            throw failure;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
