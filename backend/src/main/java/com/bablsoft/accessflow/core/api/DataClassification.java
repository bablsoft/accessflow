package com.bablsoft.accessflow.core.api;

/**
 * Data-classification labels applied to datasource tables and columns (AF-447). Locked by the
 * {@code data_classification} Postgres enum — extending the set requires a new Flyway migration
 * ({@code ALTER TYPE ... ADD VALUE}).
 *
 * <ul>
 *   <li>{@link #PII} — personally identifiable information (name, email, phone, address).</li>
 *   <li>{@link #PCI} — payment-card data (PAN, CVV) in PCI-DSS scope.</li>
 *   <li>{@link #PHI} — protected health information (HIPAA).</li>
 *   <li>{@link #GDPR} — data subject to GDPR processing rules.</li>
 *   <li>{@link #FINANCIAL} — non-card financial data (salary, balances, tax id).</li>
 *   <li>{@link #SENSITIVE} — internal-confidential catch-all.</li>
 * </ul>
 */
public enum DataClassification {
    PII,
    PCI,
    PHI,
    GDPR,
    FINANCIAL,
    SENSITIVE
}
