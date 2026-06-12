package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.datastax.oss.driver.api.core.DriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection test for a Cassandra datasource: opens a short-lived {@link com.datastax.oss.driver.api.core.CqlSession}
 * and issues {@code SELECT release_version FROM system.local} — the CQL analogue of the JDBC
 * {@code SELECT 1} probe (proving the control connection bootstraps and a query round-trips).
 */
class CassandraConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(CassandraConnectionProbe.class);
    private static final String PROBE_CQL = "SELECT release_version FROM system.local";

    private final CassandraSessionFactory sessionFactory;

    CassandraConnectionProbe(CassandraSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var session = sessionFactory.open(descriptor)) {
            session.execute(PROBE_CQL);
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (DriverException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Cassandra connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
