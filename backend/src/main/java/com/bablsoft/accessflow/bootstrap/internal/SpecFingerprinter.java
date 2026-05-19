package com.bablsoft.accessflow.bootstrap.internal;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Canonicalises a desired-state field map and returns a stable SHA-256 hex fingerprint. Used by
 * the bootstrap reconcilers to detect "no change" between the env-driven spec and the previously
 * persisted state, so a restart with unchanged env vars writes zero audit rows. Map keys are
 * sorted recursively before serialisation so the hash is deterministic across JVM restarts.
 */
@Component
public class SpecFingerprinter {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String fingerprint(Map<String, Object> fields) {
        var canonical = sort(fields == null ? Map.of() : fields);
        var json = objectMapper.writeValueAsString(canonical);
        return sha256Hex(json);
    }

    public List<String> diff(Map<String, Object> previous, Map<String, Object> current) {
        var prev = previous == null ? Map.<String, Object>of() : previous;
        var curr = current == null ? Map.<String, Object>of() : current;
        var changed = new ArrayList<String>();
        var allKeys = new TreeSet<String>();
        allKeys.addAll(prev.keySet());
        allKeys.addAll(curr.keySet());
        for (var key : allKeys) {
            if (!Objects.equals(normalise(prev.get(key)), normalise(curr.get(key)))) {
                changed.add(key);
            }
        }
        return List.copyOf(changed);
    }

    private static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sort(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(list.size());
            for (var item : list) {
                copy.add(sort(item));
            }
            return copy;
        }
        return value;
    }

    private static Object normalise(Object value) {
        if (value instanceof Map<?, ?>) {
            return sort(value);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(list.size());
            for (var item : list) {
                copy.add(normalise(item));
            }
            return copy;
        }
        return value;
    }

    private static String sha256Hex(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", ex);
        }
    }
}
