package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.SqlCanonicalizer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
class DefaultSqlCanonicalizer implements SqlCanonicalizer {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String canonicalize(String sql) {
        if (sql == null) {
            return null;
        }
        var stripped = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        stripped = LINE_COMMENT.matcher(stripped).replaceAll(" ");
        var collapsed = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.toUpperCase(Locale.ROOT);
    }
}
