package com.bablsoft.accessflow.sqlreview.internal.rules;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One list-valued parameter a rule accepts (#862). The value syntax travels with the param so the
 * write-time validator needs no knowledge of individual rules — a fifteenth parameterised rule
 * cannot slip past it unchecked.
 *
 * @param key             the JSON key inside the config row's {@code params} object
 * @param required        whether a ruleset that configures the rule must supply a non-empty list; a
 *                        required param with built-in {@code defaults} may still be omitted
 * @param defaults        the values applied when the param is absent; empty when there are none
 * @param valuePattern    the syntax every entry must match (whole-string)
 * @param invalidValueKey the {@code error.*} message key raised for an entry that does not
 */
public record SqlRuleParam(String key, boolean required, List<String> defaults, Pattern valuePattern,
                           String invalidValueKey) {
    public SqlRuleParam {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(valuePattern, "valuePattern");
        Objects.requireNonNull(invalidValueKey, "invalidValueKey");
        defaults = defaults == null ? List.of() : List.copyOf(defaults);
    }
}
