package com.bablsoft.accessflow.proxy.internal.dryrun;

import tools.jackson.databind.JsonNode;

/** Small JSON-extraction helpers shared by the JSON-plan dialects (PostgreSQL, MySQL/MariaDB). */
final class JsonPlanSupport {

    private JsonPlanSupport() {
    }

    /** A non-blank string field, or {@code null} when absent / not a string. */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var v = node.get(field);
        if (v == null || !v.isString()) {
            return null;
        }
        var s = v.stringValue();
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * A numeric field as a {@link Double}, or {@code null} when absent / non-numeric. MySQL emits
     * its cost/row figures as JSON strings (e.g. {@code "12.50"}), so numeric strings are parsed too.
     */
    static Double number(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isString()) {
            try {
                return Double.parseDouble(v.stringValue().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static Double firstNonNull(Double... values) {
        for (Double v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
