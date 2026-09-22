package com.bablsoft.accessflow.schemachange.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for schema change governance (#879, epic #870), bound from {@code accessflow.schemachange.*}.
 *
 * @param maxStatements hard cap on statements per change set. A real cost control, not a style
 *                      rule: promotion analyses every statement as a request-group member, each
 *                      re-introspecting the datasource, so an uncapped set multiplies LLM calls
 *                      and customer-database round-trips by statement count and by environment
 *                      count. {@code null} or non-positive falls back to 50.
 */
@ConfigurationProperties("accessflow.schemachange")
public record SchemaChangeProperties(Integer maxStatements) {

    public static final int DEFAULT_MAX_STATEMENTS = 50;

    public SchemaChangeProperties {
        maxStatements = maxStatements == null || maxStatements <= 0 ? DEFAULT_MAX_STATEMENTS : maxStatements;
    }
}
