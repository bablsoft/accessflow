package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;

import java.util.regex.Pattern;

/**
 * Credit-card PAN detector: 13–19 digits (spaces/dashes tolerated as grouping separators) that
 * pass the Luhn checksum. The checksum keeps arbitrary numeric ids from matching.
 */
final class CreditCardDetector implements ValueDetector {

    private static final Pattern CANDIDATE = Pattern.compile("^[0-9](?:[0-9 -]*[0-9])$");

    @Override
    public DiscoveryDetector type() {
        return DiscoveryDetector.CREDIT_CARD;
    }

    @Override
    public DataClassification classification() {
        return DataClassification.PCI;
    }

    @Override
    public boolean matches(String value) {
        var trimmed = value.trim();
        if (trimmed.length() > 30 || !CANDIDATE.matcher(trimmed).matches()) {
            return false;
        }
        var digits = trimmed.replaceAll("[ -]", "");
        return digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits);
    }

    private static boolean luhnValid(String digits) {
        var sum = 0;
        var doubleIt = false;
        for (var i = digits.length() - 1; i >= 0; i--) {
            var d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
