package com.bablsoft.accessflow.proxy.api;

/**
 * Resolves the effective row cap exactly as the executor does (#946), for read surfaces that must
 * agree with enforcement — the effective-permission explorer and the access simulator.
 */
public interface RowCapResolver {

    EffectiveRowCap resolve(Integer override, int datasourceCap);

    int globalCeiling();
}
