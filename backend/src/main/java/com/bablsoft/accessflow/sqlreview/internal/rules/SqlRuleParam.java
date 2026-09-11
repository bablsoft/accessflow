package com.bablsoft.accessflow.sqlreview.internal.rules;

import java.util.List;

/**
 * One list-valued parameter a rule accepts (#862).
 *
 * @param key      the JSON key inside the config row's {@code params} object
 * @param required whether a ruleset that configures the rule must supply a non-empty list; a
 *                 required param with built-in {@code defaults} may still be omitted
 * @param defaults the values applied when the param is absent or empty; empty when there are none
 */
public record SqlRuleParam(String key, boolean required, List<String> defaults) {
    public SqlRuleParam {
        defaults = defaults == null ? List.of() : List.copyOf(defaults);
    }
}
