package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure (no Spring) HMAC-SHA256 builder for the audit-log tamper-evident chain.
 *
 * <p>The canonical form is a length-prefixed concatenation of every audited field. Length-prefixing
 * (4-byte big-endian; -1 marks NULL with no payload) makes the encoding injective — no two
 * distinct field tuples can produce the same byte string, so collisions cannot be engineered by
 * shifting characters between fields.
 *
 * <p>Metadata is recursively normalised through {@link TreeMap}/sorted-list traversal before
 * re-serialisation, so JSON key order in the stored {@code metadata} column has no effect on the
 * hash.
 */
public final class AuditChainHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] EMPTY = new byte[0];

    private final SecretKeySpec keySpec;
    private final ObjectMapper objectMapper;

    public AuditChainHasher(byte[] key, ObjectMapper objectMapper) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("HMAC key must be at least 32 bytes");
        }
        this.keySpec = new SecretKeySpec(key.clone(), ALGORITHM);
        this.objectMapper = objectMapper;
    }

    public byte[] hash(AuditLogEntity row, byte[] previousHash) {
        var canonical = canonicalize(row);
        return mac(canonical, previousHash);
    }

    byte[] canonicalize(AuditLogEntity row) {
        var out = new ByteArrayOutputStream(256);
        writeField(out, row.getId() == null ? null : row.getId().toString());
        writeField(out, row.getOrganizationId() == null ? null : row.getOrganizationId().toString());
        writeField(out, row.getActorId() == null ? null : row.getActorId().toString());
        writeField(out, row.getAction());
        writeField(out, row.getResourceType());
        writeField(out, row.getResourceId() == null ? null : row.getResourceId().toString());
        writeField(out, canonicalJson(row.getMetadata()));
        writeField(out, row.getIpAddress());
        writeField(out, row.getUserAgent());
        writeField(out, row.getCreatedAt() == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(row.getCreatedAt()));
        return out.toByteArray();
    }

    private byte[] mac(byte[] canonical, byte[] previousHash) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            mac.update(canonical);
            mac.update(previousHash == null ? EMPTY : previousHash);
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private String canonicalJson(String storedMetadataJson) {
        if (storedMetadataJson == null || storedMetadataJson.isBlank()) {
            return "{}";
        }
        Object parsed = objectMapper.readValue(storedMetadataJson, Object.class);
        return objectMapper.writeValueAsString(normalise(parsed));
    }

    private static Object normalise(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), normalise(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            for (Object element : list) {
                copy.add(normalise(element));
            }
            return copy;
        }
        return value;
    }

    private static void writeField(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.writeBytes(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    static boolean equalHashes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
