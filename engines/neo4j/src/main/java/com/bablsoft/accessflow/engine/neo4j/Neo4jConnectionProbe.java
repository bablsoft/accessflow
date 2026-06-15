package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection test for a Neo4j datasource: opens a short-lived {@link org.neo4j.driver.Driver},
 * verifies connectivity, and runs {@code RETURN 1} against the target database — the Cypher analogue
 * of the JDBC {@code SELECT 1} probe (proving the Bolt handshake, auth, and a query round-trip).
 */
class Neo4jConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConnectionProbe.class);
    private static final String PROBE_CYPHER = "RETURN 1";

    private final Neo4jDriverFactory driverFactory;

    Neo4jConnectionProbe(Neo4jDriverFactory driverFactory) {
        this.driverFactory = driverFactory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var driver = driverFactory.open(descriptor)) {
            driver.verifyConnectivity();
            try (var session = driver.session(sessionConfig(descriptor))) {
                session.run(PROBE_CYPHER).consume();
            }
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (Neo4jException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Neo4j connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    static SessionConfig sessionConfig(DatasourceConnectionDescriptor descriptor) {
        var database = descriptor.databaseName();
        return database != null && !database.isBlank()
                ? SessionConfig.forDatabase(database.strip())
                : SessionConfig.defaultConfig();
    }
}
