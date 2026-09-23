package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;

/**
 * The outcome of resolving a drift baseline (#881): exactly one of the two is non-null.
 *
 * <p>An unresolved baseline is a first-class result, never an exception and never a silent pass —
 * the scan is still recorded, with zero findings and the reason that stopped it.
 */
record SchemaDriftBaselineResolution(DatabaseSchemaView baseline, String reasonCode) {

    static SchemaDriftBaselineResolution resolved(DatabaseSchemaView baseline) {
        return new SchemaDriftBaselineResolution(baseline, null);
    }

    static SchemaDriftBaselineResolution unresolved(String reasonCode) {
        return new SchemaDriftBaselineResolution(null, reasonCode);
    }
}
