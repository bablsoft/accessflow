package com.bablsoft.accessflow.scim.internal.protocol;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses the only filter form the supported IdPs send: {@code attribute eq "value"} (#621).
 * Anything else — other operators, and/or/not, grouping — is rejected with
 * {@code scimType=invalidFilter}, which provisioning engines handle gracefully.
 */
public final class ScimFilterParser {

    private static final Pattern EQ_FILTER = Pattern.compile(
            "^\\s*([A-Za-z0-9:._$-]+)\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$",
            Pattern.CASE_INSENSITIVE);

    private ScimFilterParser() {
    }

    /**
     * @return the parsed filter, or null when {@code filter} is null/blank (no filtering)
     * @throws ScimInvalidFilterException on any non-{@code eq} expression
     */
    public static ScimFilter parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        var matcher = EQ_FILTER.matcher(filter);
        if (!matcher.matches()) {
            throw new ScimInvalidFilterException(
                    "Unsupported filter expression; only 'attribute eq \"value\"' is supported");
        }
        var attribute = stripUrnPrefix(matcher.group(1)).toLowerCase(Locale.ROOT);
        var value = matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
        return new ScimFilter(attribute, value);
    }

    /** {@code urn:ietf:params:scim:schemas:core:2.0:User:userName} → {@code userName}. */
    private static String stripUrnPrefix(String attribute) {
        var lastColon = attribute.lastIndexOf(':');
        return lastColon >= 0 ? attribute.substring(lastColon + 1) : attribute;
    }
}
