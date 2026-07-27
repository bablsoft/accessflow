package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;

import java.util.regex.Pattern;

/**
 * Phone-number detector — an optional {@code +} country prefix and 7–15 digits with common
 * grouping punctuation ({@code ()-. } and spaces). Runs last in {@link ValueDetector#ORDERED}
 * so card numbers and SSNs (which are also digit runs) are claimed by their stricter detectors
 * first. A bare unpunctuated digit run must carry the {@code +} prefix to match — otherwise any
 * numeric id column would light up as phone numbers.
 */
final class PhoneDetector implements ValueDetector {

    private static final Pattern CANDIDATE =
            Pattern.compile("^\\+?[0-9(][0-9() .-]{5,19}[0-9)]$");
    private static final Pattern PUNCTUATED = Pattern.compile(".*[() .-].*");

    @Override
    public DiscoveryDetector type() {
        return DiscoveryDetector.PHONE;
    }

    @Override
    public DataClassification classification() {
        return DataClassification.PII;
    }

    @Override
    public boolean matches(String value) {
        var trimmed = value.trim();
        if (!CANDIDATE.matcher(trimmed).matches()) {
            return false;
        }
        if (!trimmed.startsWith("+") && !PUNCTUATED.matcher(trimmed).matches()) {
            return false;
        }
        var digits = trimmed.replaceAll("\\D", "");
        return digits.length() >= 7 && digits.length() <= 15;
    }
}
