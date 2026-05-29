package com.bablsoft.accessflow.proxy.api;

/**
 * Point-in-time HikariCP connection-pool gauges for a single datasource, read from the live
 * {@code HikariPoolMXBean}. {@code active + idle} normally equals {@code total}; {@code waiting}
 * is the number of threads blocked waiting for a connection (a positive value signals pool
 * pressure). {@code max} is the configured ceiling.
 */
public record DatasourcePoolStats(int active, int idle, int waiting, int total, int max) {
}
