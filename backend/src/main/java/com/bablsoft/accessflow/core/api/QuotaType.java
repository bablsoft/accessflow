package com.bablsoft.accessflow.core.api;

/**
 * The per-org quota dimensions enforced at the service layer (AF-456).
 */
public enum QuotaType {
    DATASOURCE,
    USER,
    QUERIES_PER_DAY
}
