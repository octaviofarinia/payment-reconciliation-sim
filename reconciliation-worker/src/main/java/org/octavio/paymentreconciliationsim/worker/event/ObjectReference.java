package org.octavio.paymentreconciliationsim.worker.event;
/** Exact registered object version; a latest-version read is never permitted. */
public record ObjectReference(String runId, String bucket, String key, String versionId) {}
