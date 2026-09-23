package com.bablsoft.accessflow.schemachange.internal;

/**
 * What one scheduled scan of one environment amounted to (#881), for the pipeline-level bookkeeping
 * the coordinator does once every environment has been visited.
 *
 * @param ran     {@code false} when another replica held the environment's lock and nothing ran
 * @param failure the reason code when the scan failed (see {@link SchemaDriftScanReason#isFailure}),
 *                otherwise null — an inapplicable engine or a missing baseline is not a failure
 */
record SchemaDriftScanRun(boolean ran, String failure) {

    static final SchemaDriftScanRun SKIPPED = new SchemaDriftScanRun(false, null);
}
