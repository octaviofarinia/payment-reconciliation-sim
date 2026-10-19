package org.octavio.paymentreconciliationsim.worker.storage;

import org.octavio.paymentreconciliationsim.worker.InputRejected;
import org.octavio.paymentreconciliationsim.worker.event.ObjectReference;
import software.amazon.awssdk.checksums.DefaultChecksumAlgorithm;
import software.amazon.awssdk.checksums.SdkChecksum;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bounds transport reads and verifies raw bytes against registration before binding or parsing.
 */
public final class S3SettlementReader {
    private final S3Client s3;

    public S3SettlementReader(S3Client s3) {
        this.s3 = s3;
    }

    private static void size(Long length, long expected) {
        if (length == null || length != expected) throw new InputRejected("OBJECT_SIZE_MISMATCH");
    }

    private static void version(String actual, String expected) {
        if (actual != null && !expected.equals(actual)) throw new InputRejected("OBJECT_VERSION_MISMATCH");
    }

    private static void checksum(String actual, String expected) {
        if (actual != null && !expected.equals(actual)) throw new InputRejected("OBJECT_CHECKSUM_MISMATCH");
    }

    public byte[] read(ObjectReference object, long expectedLength, String sha256) throws IOException {
        if (expectedLength < 1 || expectedLength > 2097152) throw new InputRejected("OBJECT_SIZE_MISMATCH");
        var head = s3.headObject(HeadObjectRequest.builder().bucket(object.bucket()).key(object.key()).versionId(object.versionId()).checksumMode(ChecksumMode.ENABLED).build());
        size(head.contentLength(), expectedLength);
        version(head.versionId(), object.versionId());
        byte[] bytes;
        String responseChecksum;
        try (var stream = s3.getObject(GetObjectRequest.builder().bucket(object.bucket()).key(object.key()).versionId(object.versionId()).checksumMode(ChecksumMode.ENABLED).build())) {
            size(stream.response().contentLength(), expectedLength);
            version(stream.response().versionId(), object.versionId());
            responseChecksum = stream.response().checksumSHA256();
            bytes = stream.readNBytes(Math.toIntExact(expectedLength + 1));
        }
        if (bytes.length != expectedLength) throw new InputRejected("OBJECT_SIZE_MISMATCH");
        var checksum = SdkChecksum.forAlgorithm(DefaultChecksumAlgorithm.SHA256);
        checksum.update(bytes);
        byte[] digest = checksum.getChecksumBytes();
        if (!sha256.equals(HexFormat.of().formatHex(digest))) throw new InputRejected("OBJECT_CHECKSUM_MISMATCH");
        String base64 = Base64.getEncoder().encodeToString(digest);
        checksum(head.checksumSHA256(), base64);
        checksum(responseChecksum, base64);
        return bytes;
    }
}
