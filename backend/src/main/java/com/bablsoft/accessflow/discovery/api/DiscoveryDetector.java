package com.bablsoft.accessflow.discovery.api;

/**
 * How a discovery finding was produced (AF-623). Mirrors the PostgreSQL enum
 * {@code discovery_detector}. The regex/checksum detectors run locally on sampled values;
 * {@code AI} rows come from the optional pass through the org's bound AI analyzer.
 */
public enum DiscoveryDetector {
    EMAIL,
    CREDIT_CARD,
    SSN,
    IBAN,
    PHONE,
    AI
}
