package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;

import java.util.regex.Pattern;

/** Pragmatic RFC-lite email address detector — local part, {@code @}, dotted domain with TLD. */
final class EmailDetector implements ValueDetector {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    @Override
    public DiscoveryDetector type() {
        return DiscoveryDetector.EMAIL;
    }

    @Override
    public DataClassification classification() {
        return DataClassification.PII;
    }

    @Override
    public boolean matches(String value) {
        return value.length() <= 320 && EMAIL.matcher(value.trim()).matches();
    }
}
