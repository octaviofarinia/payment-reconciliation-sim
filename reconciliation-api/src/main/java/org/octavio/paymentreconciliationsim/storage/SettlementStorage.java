package org.octavio.paymentreconciliationsim.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.octavio.paymentreconciliationsim.run.RunContracts.ObjectIdentity;
import org.octavio.paymentreconciliationsim.run.RunContracts.RunMetadata;

/** API-owned outbound port for immutable settlement input storage. */
public interface SettlementStorage {
    UploadInstructions upload(String objectKey, String sha256, long byteLength);

    /** Empty means the registered input must be uploaded again; never replaces an existing binding. */
    Optional<ObjectIdentity> recoverUploadedVersion(RunMetadata run);

    record UploadInstructions(String url, Map<String, String> requiredHeaders, Instant expiresAt) {}
}
