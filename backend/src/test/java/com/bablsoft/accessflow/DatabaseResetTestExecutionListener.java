package com.bablsoft.accessflow;

import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import javax.sql.DataSource;

import java.util.UUID;

/**
 * Truncates the shared Testcontainers Postgres after every test class, then restores the V114
 * system-role seed.
 *
 * <p>Why this exists: the suite used to build a fresh Spring context per test class, and each
 * eviction closed the "shared" static Postgres — so most classes silently got a brand-new database.
 * Now that the test-context cache actually works there is exactly one Postgres for the whole run,
 * and {@code users.email} is globally UNIQUE while 94 of 127 integration tests clean up with
 * blanket {@code deleteAll()} calls. Any row a class leaks — because it threw before its cleanup,
 * or deleted parents before children — would become the next class's unique or FK violation. This
 * listener makes inter-class isolation unconditional instead of relying on each test's own cleanup.
 *
 * <p>Registered globally via {@code META-INF/spring.factories}. TestExecutionListeners are not part
 * of {@code MergedContextConfiguration}, so this costs nothing in the context cache.
 *
 * <p>Runs in {@code afterTestClass} and only when the class actually built a context
 * ({@code hasApplicationContext()}), so plain unit tests and the handful of integration tests that
 * drive raw Testcontainers without Spring are skipped and no context is forced to load.
 */
public class DatabaseResetTestExecutionListener extends AbstractTestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DatabaseResetTestExecutionListener.class);

    /**
     * Truncate the tables that actually hold rows, skipping Flyway's history.
     *
     * <p>Enumerated from the catalog rather than hardcoded, so migrations that add tables need no
     * change here. The {@code EXISTS} pre-pass matters for wall clock: the schema has ~98 tables
     * and a typical test class touches a handful, and one {@code TRUNCATE} naming ~98 tables takes
     * an {@code ACCESS EXCLUSIVE} lock and rewrites a relation file for each. Probing first is far
     * cheaper than truncating empty tables 127 times over.
     *
     * <p>No {@code RESTART IDENTITY}: every table keys off a UUID, and the schema declares no
     * serial column or sequence.
     *
     * <p>{@code roles} and {@code role_permissions} are the only tables carrying seeded reference
     * data, and {@link TestSystemRoleSeeder} restores those;
     * {@link SeededReferenceDataParityTest} fails the build if a migration ever seeds another.
     */
    private static final String TRUNCATE_NON_EMPTY = """
            DO $$
            DECLARE
              candidate text;
              occupied  text[] := '{}';
              has_rows  boolean;
            BEGIN
              FOR candidate IN
                SELECT format('%I.%I', schemaname, tablename)
                  FROM pg_tables
                 WHERE schemaname = 'public'
                   AND tablename <> 'flyway_schema_history'
              LOOP
                EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s)', candidate) INTO has_rows;
                IF has_rows THEN
                  occupied := occupied || candidate;
                END IF;
              END LOOP;
              IF array_length(occupied, 1) IS NOT NULL THEN
                EXECUTE 'TRUNCATE TABLE ' || array_to_string(occupied, ', ') || ' CASCADE';
              END IF;
            END $$;""";

    private static final int MAX_RESET_ATTEMPTS = 2;

    @Override
    public int getOrder() {
        return 10_000;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        var dataSource = testContext.getApplicationContext().getBeanProvider(DataSource.class).getIfAvailable();
        if (dataSource == null) {
            return;
        }
        var jdbcTemplate = new JdbcTemplate(dataSource);
        evictDatasourcePools(testContext, jdbcTemplate);
        reset(jdbcTemplate, testContext);
    }

    /**
     * Truncate and re-seed, retrying once on a lock failure.
     *
     * <p>{@code TRUNCATE} takes an {@code ACCESS EXCLUSIVE} lock on every table it touches, and
     * this codebase writes audit rows from asynchronous listeners on virtual threads. A listener
     * whose transaction is still committing as the reset starts can deadlock with it — the same
     * intermittent failure the eight test classes that truncate in {@code @AfterEach} already see.
     * The loser is chosen at random and the winner finishes immediately, so one retry clears it.
     * {@code CannotAcquireLockException} is a subtype of the caught exception, so both are covered.
     * Failing here would abort an unrelated test class, so it is worth the retry.
     */
    private void reset(JdbcTemplate jdbcTemplate, TestContext testContext) {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbcTemplate.execute(TRUNCATE_NON_EMPTY);
                TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
                return;
            } catch (PessimisticLockingFailureException e) {
                if (attempt > MAX_RESET_ATTEMPTS) {
                    log.error("Failed to reset the shared test database after {}",
                            testContext.getTestClass().getName(), e);
                    throw e;
                }
                log.warn("Lock contention resetting the test database after {}; retrying",
                        testContext.getTestClass().getName(), e);
            }
        }
    }

    /**
     * Close the HikariCP pools the proxy holds for the rows we are about to truncate.
     *
     * <p>Without this, a pool outlives the test class that created its datasource: the context is
     * now shared, so {@code DatasourceConnectionPoolManager} keeps the pool cached long after the
     * test's Testcontainers instance has stopped, and Hikari's connection-adder thread retries
     * against a dead port for the rest of the run. Harmless but noisy, and it leaks a thread per
     * abandoned datasource.
     */
    private void evictDatasourcePools(TestContext testContext, JdbcTemplate jdbcTemplate) {
        var poolManager = testContext.getApplicationContext()
                .getBeanProvider(DatasourceConnectionPoolManager.class).getIfAvailable();
        if (poolManager == null) {
            return;
        }
        for (UUID id : jdbcTemplate.queryForList("SELECT id FROM datasources", UUID.class)) {
            poolManager.evict(id);
        }
    }

}
