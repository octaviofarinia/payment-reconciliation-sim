package org.octavio.paymentreconciliationsim.acceptance.support;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** Path-style, version-aware S3 transport substitute. Does not validate SigV4 authorization. */
public final class S3Stub implements AutoCloseable {
    private record ObjectKey(String bucket, String key) {}
    private record StoredObject(String version, byte[] bytes, String sha256, String etag, Instant storedAt) {}
    private final Map<ObjectKey, LinkedHashMap<String, StoredObject>> objects = new HashMap<>();
    private final WireMockServer server = new WireMockServer(options().dynamicPort().extensions(new ObjectResponses()));

    private URI endpoint;

    public void start() {
        server.start();
        endpoint = URI.create(server.baseUrl());
        server.stubFor(any(urlMatching("/.*")).willReturn(aResponse().withTransformers("s3-objects")));
    }
    public URI endpoint() { return endpoint; }
    public boolean isRunning() { return server.isRunning(); }

    public synchronized String store(String bucket, String key, byte[] bytes) {
        String version = UUID.randomUUID().toString();
        byte[] copy = bytes.clone();
        var object = new StoredObject(version, copy, Base64.getEncoder().encodeToString(digest("SHA-256", copy)),
                "\"" + HexFormat.of().formatHex(digest("MD5", copy)) + "\"", Instant.now());
        objects.computeIfAbsent(new ObjectKey(bucket, key), ignored -> new LinkedHashMap<>()).put(version, object);
        return version;
    }

    public synchronized byte[] get(String bucket, String key, String versionId) {
        StoredObject object = lookup(bucket, key, versionId);
        return object == null ? null : object.bytes().clone();
    }

    public synchronized void reset() { objects.clear(); server.resetRequests(); }
    public void close() { server.stop(); }

    private StoredObject lookup(String bucket, String key, String version) {
        var versions = objects.get(new ObjectKey(bucket, key));
        if (versions == null) return null;
        return version == null ? versions.lastEntry().getValue() : versions.get(version);
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try { return MessageDigest.getInstance(algorithm).digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String decodePath(String path) {
        // URLDecoder follows form semantics; protect literal plus signs in S3 paths.
        return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static byte[] decodeAwsChunks(byte[] wireBytes, int expectedLength) {
        var decoded = new ByteArrayOutputStream();
        int cursor = 0;
        while (cursor < wireBytes.length) {
            int lineEnd = cursor;
            while (lineEnd + 1 < wireBytes.length && !(wireBytes[lineEnd] == 13 && wireBytes[lineEnd + 1] == 10)) lineEnd++;
            if (lineEnd + 1 >= wireBytes.length) throw new IllegalArgumentException("Missing AWS chunk size");
            String sizeLine = new String(wireBytes, cursor, lineEnd - cursor, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(sizeLine.split(";", 2)[0], 16);
            cursor = lineEnd + 2;
            if (size == 0) {
                if (decoded.size() != expectedLength) throw new IllegalArgumentException("AWS decoded length mismatch");
                return decoded.toByteArray();
            }
            if (size < 0 || size > wireBytes.length - cursor - 2 || decoded.size() > expectedLength - size
                    || wireBytes[cursor + size] != 13 || wireBytes[cursor + size + 1] != 10) {
                throw new IllegalArgumentException("Malformed AWS chunk");
            }
            decoded.write(wireBytes, cursor, size);
            cursor += size + 2;
        }
        throw new IllegalArgumentException("Missing AWS terminal chunk");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private ResponseDefinition listVersions(String bucket, String prefix) {
        var body = new StringBuilder("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
                .append("<Name>").append(xml(bucket)).append("</Name><Prefix>").append(xml(prefix))
                .append("</Prefix><IsTruncated>false</IsTruncated>");
        for (var entry : objects.entrySet()) {
            if (entry.getKey().bucket().equals(bucket) && entry.getKey().key().startsWith(prefix)) {
                for (var object : entry.getValue().values()) {
                    body.append("<Version><Key>").append(xml(entry.getKey().key()))
                            .append("</Key><VersionId>").append(xml(object.version()))
                            .append("</VersionId><IsLatest>").append(object == entry.getValue().lastEntry().getValue())
                            .append("</IsLatest><LastModified>").append(object.storedAt())
                            .append("</LastModified><ETag>").append(xml(object.etag()))
                            .append("</ETag><Size>").append(object.bytes().length).append("</Size></Version>");
                }
            }
        }
        body.append("</ListVersionsResult>");
        return aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(body.toString()).build();
    }

    private final class ObjectResponses implements ResponseDefinitionTransformerV2 {
        public String getName() { return "s3-objects"; }
        public boolean applyGlobally() { return false; }
        public ResponseDefinition transform(ServeEvent event) {
            var request = event.getRequest();
            URI uri = URI.create(request.getUrl());
            String[] path = uri.getRawPath().split("/", 3);
            if (path.length == 2 && request.queryParameter("versions").isPresent()
                    && "GET".equals(request.getMethod().getName())) {
                synchronized (S3Stub.this) {
                    String prefix = request.queryParameter("prefix").isPresent() ? request.queryParameter("prefix").firstValue() : "";
                    return listVersions(decodePath(path[1]), prefix);
                }
            }
            if (path.length != 3) return aResponse().withStatus(400).build();
            String bucket = decodePath(path[1]);
            String key = decodePath(path[2]);
            String method = request.getMethod().getName();
            synchronized (S3Stub.this) {
                var versionParameter = request.queryParameter("versionId");
                String version = versionParameter.isPresent() ? versionParameter.firstValue() : null;
                if ("PUT".equals(method)) {
                    byte[] bytes = request.getBody();
                    String decodedLength = request.getHeader("x-amz-decoded-content-length");
                    if (decodedLength != null) {
                        try { bytes = decodeAwsChunks(bytes, Integer.parseInt(decodedLength)); }
                        catch (IllegalArgumentException invalid) { return aResponse().withStatus(400).build(); }
                    }
                    if (bytes.length > 2097152) return aResponse().withStatus(413).build();
                    String suppliedChecksum = request.getHeader("x-amz-checksum-sha256");
                    String actualChecksum = Base64.getEncoder().encodeToString(digest("SHA-256", bytes));
                    if (suppliedChecksum != null && !suppliedChecksum.equals(actualChecksum)) {
                        return aResponse().withStatus(400).withHeader("Content-Type", "application/xml")
                                .withBody("<Error><Code>BadDigest</Code></Error>").build();
                    }
                    version = store(bucket, key, bytes);
                } else if (!"HEAD".equals(method) && !"GET".equals(method)) {
                    return aResponse().withStatus(405).build();
                }
                var object = lookup(bucket, key, version);
                if (object == null) return aResponse().withStatus(404).withHeader("Content-Type", "application/xml")
                        .withBody("<Error><Code>NoSuchKey</Code></Error>").build();
                ResponseDefinitionBuilder response = aResponse().withStatus(200)
                        .withHeader("x-amz-version-id", object.version())
                        .withHeader("x-amz-checksum-sha256", object.sha256())
                        .withHeader("ETag", object.etag()).withHeader("Content-Type", "application/octet-stream")
                        .withHeader("x-amz-request-id", "acceptance-request");
                if ("PUT".equals(method)) return response.withHeader("Content-Length", "0").build();
                response.withHeader("Content-Length", Integer.toString(object.bytes().length));
                if ("GET".equals(method)) response.withBody(object.bytes());
                return response.build();
            }
        }
    }
}
