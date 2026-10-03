package org.octavio.paymentreconciliationsim.storage;
import java.time.Instant;
import java.util.Map;
/** Outbound port; Task 7 supplies S3 signing and implementation. */
public interface SettlementStorage {
 UploadInstructions upload(String objectKey,String sha256,long byteLength);
 record UploadInstructions(String url,Map<String,String> requiredHeaders,Instant expiresAt){}
}
