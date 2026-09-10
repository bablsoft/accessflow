package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Who can reach one table with one class of statement (issue AF-859).
 *
 * @param table normalized by the implementation through the same rules the enforcement gate applies
 *              to an allow-list entry — quotes and brackets stripped, trimmed, lower-cased
 */
public record EffectiveAccessQuery(UUID datasourceId, String table,
                                   StatementCapability capability) {
}
