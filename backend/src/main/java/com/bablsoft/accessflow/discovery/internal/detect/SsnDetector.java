package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;

import java.util.regex.Pattern;

/**
 * US Social Security number detector — {@code AAA-GG-SSSS} (dashes optional but consistent),
 * rejecting the never-issued ranges: area 000/666/9xx, group 00, serial 0000.
 */
final class SsnDetector implements ValueDetector {

    private static final Pattern DASHED = Pattern.compile("^(\\d{3})-(\\d{2})-(\\d{4})$");
    private static final Pattern PLAIN = Pattern.compile("^(\\d{3})(\\d{2})(\\d{4})$");

    @Override
    public DiscoveryDetector type() {
        return DiscoveryDetector.SSN;
    }

    @Override
    public DataClassification classification() {
        return DataClassification.PII;
    }

    @Override
    public boolean matches(String value) {
        var trimmed = value.trim();
        var matcher = DASHED.matcher(trimmed);
        if (!matcher.matches()) {
            matcher = PLAIN.matcher(trimmed);
            if (!matcher.matches()) {
                return false;
            }
        }
        var area = Integer.parseInt(matcher.group(1));
        var group = Integer.parseInt(matcher.group(2));
        var serial = Integer.parseInt(matcher.group(3));
        return area != 0 && area != 666 && area < 900 && group != 0 && serial != 0;
    }
}
