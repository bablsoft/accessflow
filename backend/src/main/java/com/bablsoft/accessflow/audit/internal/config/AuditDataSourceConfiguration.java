package com.bablsoft.accessflow.audit.internal.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import javax.sql.DataSource;

/**
 * Wires two {@link DataSource}s for the AccessFlow database:
 * <ul>
 *   <li>The {@link Primary @Primary} {@code dataSource}, used by Hibernate / JPA / Flyway and
 *       every general application query. Built from
 *       {@link JdbcConnectionDetails} when available (e.g. Testcontainers
 *       {@code @ServiceConnection}) and from {@code spring.datasource.*} otherwise —
 *       mirroring Spring Boot's auto-configured shape. Adding our own ensures Boot's JPA,
 *       Flyway, and JdbcTemplate auto-configs can still pick a single candidate after the
 *       audit-only {@code auditDataSource} bean is introduced.</li>
 *   <li>{@code auditDataSource}, a dedicated pool that authenticates as
 *       {@code accessflow.audit.datasource.username} (typically the {@code AUDIT_DB_USER}
 *       env var) — a separate Postgres role that owns {@code audit_log} so the general
 *       {@code DB_USER} can have UPDATE/DELETE/TRUNCATE revoked from it. The URL is
 *       inherited from the primary connection details.</li>
 * </ul>
 *
 * <p>Audit reads ({@code AuditLogService.query} / {@code verify}) continue to use the
 * primary {@link DataSource} via the JPA repository — the general {@code DB_USER} retains
 * SELECT on {@code audit_log}. Only the INSERT path is routed through the audit role.
 */
@Configuration
@EnableConfigurationProperties(AuditDataSourceProperties.class)
class AuditDataSourceConfiguration {

    static final String DATA_SOURCE_BEAN = "auditDataSource";
    static final String JDBC_TEMPLATE_BEAN = "auditJdbcTemplate";
    static final String TX_MANAGER_BEAN = "auditTransactionManager";
    static final String TX_TEMPLATE_BEAN = "auditTransactionTemplate";

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    DataSource dataSource(DataSourceProperties primaryDataSourceProperties,
                          ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider) {
        var connection = resolveConnection(primaryDataSourceProperties, connectionDetailsProvider);
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(connection.driverClassName())
                .url(connection.url())
                .username(connection.username())
                .password(connection.password())
                .build();
    }

    @Bean(DATA_SOURCE_BEAN)
    DataSource auditDataSource(AuditDataSourceProperties properties,
                               DataSourceProperties primaryDataSourceProperties,
                               ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider) {
        if (properties.username() == null || properties.username().isBlank()) {
            throw new IllegalStateException(
                    "accessflow.audit.datasource.username (env AUDIT_DB_USER) is required — "
                            + "see docs/09-deployment.md → \"audit_log role separation\".");
        }
        if (properties.password() == null) {
            throw new IllegalStateException(
                    "accessflow.audit.datasource.password (env AUDIT_DB_PASSWORD) is required — "
                            + "see docs/09-deployment.md → \"audit_log role separation\".");
        }
        var connection = resolveConnection(primaryDataSourceProperties, connectionDetailsProvider);
        var ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(connection.driverClassName())
                .url(connection.url())
                .username(properties.username())
                .password(properties.password())
                .build();
        ds.setPoolName("accessflow-audit");
        // Audit writes are short-lived and serialized per-org by pg_advisory_xact_lock; a
        // tiny pool is plenty and keeps the connection count to the database low.
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        return ds;
    }

    @Bean(JDBC_TEMPLATE_BEAN)
    JdbcTemplate auditJdbcTemplate(DataSource auditDataSource) {
        return new JdbcTemplate(auditDataSource);
    }

    /**
     * The primary JPA transaction manager, named {@code transactionManager} so that the
     * default {@code @Transactional} qualifier picks it. Spring Boot's
     * {@code JpaBaseConfiguration#transactionManager} bails out via
     * {@code @ConditionalOnMissingBean(TransactionManager.class)} as soon as we add our
     * {@link #auditTransactionManager} below, so we have to register the primary one
     * ourselves.
     */
    @Bean
    @Primary
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean(TX_MANAGER_BEAN)
    DataSourceTransactionManager auditTransactionManager(DataSource auditDataSource) {
        return new DataSourceTransactionManager(auditDataSource);
    }

    @Bean(TX_TEMPLATE_BEAN)
    TransactionTemplate auditTransactionTemplate(DataSourceTransactionManager auditTransactionManager) {
        return new TransactionTemplate(auditTransactionManager);
    }

    private static ResolvedConnection resolveConnection(
            DataSourceProperties properties,
            ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider) {
        var details = connectionDetailsProvider.getIfAvailable();
        if (details != null) {
            return new ResolvedConnection(
                    details.getJdbcUrl(),
                    details.getUsername(),
                    details.getPassword(),
                    details.getDriverClassName());
        }
        return new ResolvedConnection(
                properties.determineUrl(),
                properties.determineUsername(),
                properties.determinePassword(),
                properties.determineDriverClassName());
    }

    private record ResolvedConnection(String url, String username, String password, String driverClassName) {}
}
