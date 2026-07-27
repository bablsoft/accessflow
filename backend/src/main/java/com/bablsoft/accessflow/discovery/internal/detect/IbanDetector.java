package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;

import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * IBAN detector — country prefix with the official per-country length, then the ISO 13616
 * mod-97 checksum (rearrange first four chars to the end, letters → 10..35, remainder must be 1).
 */
final class IbanDetector implements ValueDetector {

    private static final Pattern CANDIDATE =
            Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Za-z0-9 ]{10,36}$");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    /** Official IBAN lengths for common registries (subset; unknown countries never match). */
    private static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28), Map.entry("AT", 20),
            Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("CH", 21),
            Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22), Map.entry("DK", 18),
            Map.entry("EE", 20), Map.entry("ES", 24), Map.entry("FI", 18), Map.entry("FR", 27),
            Map.entry("GB", 22), Map.entry("GE", 22), Map.entry("GR", 27), Map.entry("HR", 21),
            Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IS", 26),
            Map.entry("IT", 27), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("MC", 27), Map.entry("MD", 24), Map.entry("ME", 22),
            Map.entry("MK", 19), Map.entry("MT", 31), Map.entry("NL", 18), Map.entry("NO", 15),
            Map.entry("PL", 28), Map.entry("PT", 25), Map.entry("RO", 24), Map.entry("RS", 22),
            Map.entry("SA", 24), Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24),
            Map.entry("SM", 27), Map.entry("TR", 26), Map.entry("UA", 29), Map.entry("XK", 20));

    @Override
    public DiscoveryDetector type() {
        return DiscoveryDetector.IBAN;
    }

    @Override
    public DataClassification classification() {
        return DataClassification.FINANCIAL;
    }

    @Override
    public boolean matches(String value) {
        var trimmed = value.trim().toUpperCase();
        if (trimmed.length() > 42 || !CANDIDATE.matcher(trimmed).matches()) {
            return false;
        }
        var compact = trimmed.replace(" ", "");
        var expected = LENGTHS.get(compact.substring(0, 2));
        if (expected == null || compact.length() != expected) {
            return false;
        }
        return mod97(compact.substring(4) + compact.substring(0, 4)) == 1;
    }

    private static int mod97(String rearranged) {
        var sb = new StringBuilder(rearranged.length() * 2);
        for (var i = 0; i < rearranged.length(); i++) {
            var c = rearranged.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                sb.append(c - 'A' + 10);
            }
        }
        return new BigInteger(sb.toString()).mod(NINETY_SEVEN).intValue();
    }
}
