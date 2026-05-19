package com.bablsoft.accessflow.bootstrap.internal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecFingerprinterTest {

    private final SpecFingerprinter fingerprinter = new SpecFingerprinter();

    @Test
    void identicalMapsProduceIdenticalFingerprints() {
        var a = Map.<String, Object>of("name", "alpha", "port", 5432);
        var b = Map.<String, Object>of("name", "alpha", "port", 5432);

        assertThat(fingerprinter.fingerprint(a)).isEqualTo(fingerprinter.fingerprint(b));
    }

    @Test
    void keyOrderDoesNotChangeFingerprint() {
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("a", 1);
        ordered.put("b", 2);
        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("b", 2);
        reversed.put("a", 1);

        assertThat(fingerprinter.fingerprint(ordered)).isEqualTo(fingerprinter.fingerprint(reversed));
    }

    @Test
    void valueChangeFlipsFingerprint() {
        var before = Map.<String, Object>of("password", "old");
        var after = Map.<String, Object>of("password", "new");

        assertThat(fingerprinter.fingerprint(before)).isNotEqualTo(fingerprinter.fingerprint(after));
    }

    @Test
    void nullAndBlankAreDistinct() {
        var withNull = new LinkedHashMap<String, Object>();
        withNull.put("endpoint", null);
        var withBlank = Map.<String, Object>of("endpoint", "");

        assertThat(fingerprinter.fingerprint(withNull)).isNotEqualTo(fingerprinter.fingerprint(withBlank));
    }

    @Test
    void emptyMapHashesDeterministically() {
        assertThat(fingerprinter.fingerprint(Map.of()))
                .isEqualTo(fingerprinter.fingerprint(Map.of()))
                .hasSize(64);
    }

    @Test
    void nestedMapsAreCanonicalised() {
        var a = Map.<String, Object>of("config", Map.of("a", 1, "b", 2));
        var b = Map.<String, Object>of("config", Map.of("b", 2, "a", 1));

        assertThat(fingerprinter.fingerprint(a)).isEqualTo(fingerprinter.fingerprint(b));
    }

    @Test
    void listOrderIsPreservedInFingerprint() {
        var a = Map.<String, Object>of("approvers", List.of("alice", "bob"));
        var b = Map.<String, Object>of("approvers", List.of("bob", "alice"));

        assertThat(fingerprinter.fingerprint(a)).isNotEqualTo(fingerprinter.fingerprint(b));
    }

    @Test
    void nullInputHashesLikeEmptyMap() {
        assertThat(fingerprinter.fingerprint(null)).isEqualTo(fingerprinter.fingerprint(Map.of()));
    }

    @Test
    void diffReportsOnlyChangedKeys() {
        var prev = Map.<String, Object>of("name", "alpha", "port", 5432, "host", "localhost");
        var curr = Map.<String, Object>of("name", "alpha", "port", 6543, "host", "localhost");

        assertThat(fingerprinter.diff(prev, curr)).containsExactly("port");
    }

    @Test
    void diffIncludesAddedAndRemovedKeys() {
        var prev = Map.<String, Object>of("a", 1);
        var curr = Map.<String, Object>of("b", 2);

        assertThat(fingerprinter.diff(prev, curr)).containsExactly("a", "b");
    }

    @Test
    void diffOnEqualMapsReturnsEmpty() {
        var prev = Map.<String, Object>of("a", 1, "b", "two");
        var curr = Map.<String, Object>of("a", 1, "b", "two");

        assertThat(fingerprinter.diff(prev, curr)).isEmpty();
    }

    @Test
    void diffHandlesNullsAsEmpty() {
        assertThat(fingerprinter.diff(null, null)).isEmpty();
        assertThat(fingerprinter.diff(null, Map.of("a", 1))).containsExactly("a");
    }
}
