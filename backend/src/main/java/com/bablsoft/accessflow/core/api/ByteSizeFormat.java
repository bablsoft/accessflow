package com.bablsoft.accessflow.core.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders a raw byte count for a user-facing message, in decimal units (the unit warehouses bill
 * in), keeping the exact figure alongside so a value just over a limit never reads as equal to it:
 * {@code 1.5 TB (1500000000000 B)} — the unit symbols need no translation inside a localized message. Mirrors the frontend's {@code formatBytes}.
 */
public final class ByteSizeFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};

    private ByteSizeFormat() {
    }

    public static String format(long bytes) {
        if (bytes < 1000) {
            return bytes + " B";
        }
        var scaled = BigDecimal.valueOf(bytes);
        var thousand = BigDecimal.valueOf(1000);
        int unit = 0;
        while (scaled.compareTo(thousand) >= 0 && unit < UNITS.length - 1) {
            scaled = scaled.divide(thousand);
            unit++;
        }
        var rounded = scaled.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return rounded + " " + UNITS[unit] + " (" + bytes + " B)";
    }
}
