package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;
import java.util.Objects;

/**
 * One list-valued parameter a built-in rule accepts, as exposed by the rule catalog (#863).
 *
 * @param key          the JSON key inside a rule config's {@code params} object
 * @param required     whether a config that names the rule must supply a non-empty list (a required
 *                     param with non-empty {@code defaults} may still be omitted)
 * @param defaults     the values applied when the param is absent; never {@code null}
 * @param valuePattern the whole-string regular expression every entry must match
 */
public record SqlReviewRuleParamView(String key, boolean required, List<String> defaults, String valuePattern) {
    public SqlReviewRuleParamView {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(valuePattern, "valuePattern");
        defaults = defaults == null ? List.of() : List.copyOf(defaults);
    }
}
