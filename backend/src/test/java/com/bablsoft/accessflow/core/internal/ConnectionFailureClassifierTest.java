package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ConnectionFailureReason;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionFailureClassifierTest {

    private static ConnectionFailureReason classify(SQLException failure) {
        return ConnectionFailureClassifier.classify(failure);
    }

    @Test
    void sslHandshakeInTheCauseChainIsSsl() {
        var failure = new SQLException("SSL error", "08006",
                new RuntimeException(new SSLHandshakeException("PKIX path building failed")));
        assertThat(classify(failure)).isEqualTo(ConnectionFailureReason.SSL);
    }

    @Test
    void serverWithoutSslIsSslByMessage() {
        assertThat(classify(new SQLException("The server does not support SSL.", "08004")))
                .isEqualTo(ConnectionFailureReason.SSL);
        assertThat(classify(new SQLException("unable to find valid certification path", "08001")))
                .isEqualTo(ConnectionFailureReason.SSL);
    }

    @Test
    void unresolvedOrRefusedHostIsUnreachable() {
        assertThat(classify(new SQLException("x", "08001", new UnknownHostException("db.internal"))))
                .isEqualTo(ConnectionFailureReason.HOST_UNREACHABLE);
        assertThat(classify(new SQLException("x", "08001", new ConnectException("refused"))))
                .isEqualTo(ConnectionFailureReason.HOST_UNREACHABLE);
        assertThat(classify(new SQLException("x", null, new NoRouteToHostException("no route"))))
                .isEqualTo(ConnectionFailureReason.HOST_UNREACHABLE);
    }

    @Test
    void connectionExceptionStateWithoutACauseIsUnreachable() {
        assertThat(classify(new SQLException("Communications link failure", "08S01")))
                .isEqualTo(ConnectionFailureReason.HOST_UNREACHABLE);
    }

    @Test
    void socketOrStatementTimeoutIsTimeout() {
        assertThat(classify(new SQLException("x", "08001", new SocketTimeoutException("connect"))))
                .isEqualTo(ConnectionFailureReason.TIMEOUT);
        assertThat(classify(new SQLTimeoutException("login timed out")))
                .isEqualTo(ConnectionFailureReason.TIMEOUT);
        assertThat(classify(new SQLException("Connection attempt timed out.", "08001")))
                .isEqualTo(ConnectionFailureReason.TIMEOUT);
    }

    @Test
    void invalidAuthorizationStateIsAuthentication() {
        assertThat(classify(new SQLException("password authentication failed", "28P01")))
                .isEqualTo(ConnectionFailureReason.AUTHENTICATION);
        assertThat(classify(new SQLException("Access denied for user", "28000", 1045)))
                .isEqualTo(ConnectionFailureReason.AUTHENTICATION);
    }

    @Test
    void vendorLoginCodesAreAuthentication() {
        assertThat(classify(new SQLException("Login failed for user 'sa'.", "S0001", 18456)))
                .isEqualTo(ConnectionFailureReason.AUTHENTICATION);
        assertThat(classify(new SQLException("ORA-01017: invalid credential", "72000", 1017)))
                .isEqualTo(ConnectionFailureReason.AUTHENTICATION);
    }

    @Test
    void missingDatabaseIsDatabaseNotFound() {
        assertThat(classify(new SQLException("database \"nope\" does not exist", "3D000")))
                .isEqualTo(ConnectionFailureReason.DATABASE_NOT_FOUND);
        assertThat(classify(new SQLException("Unknown database 'nope'", "42000", 1049)))
                .isEqualTo(ConnectionFailureReason.DATABASE_NOT_FOUND);
        assertThat(classify(new SQLException("Cannot open database", "S0001", 4060)))
                .isEqualTo(ConnectionFailureReason.DATABASE_NOT_FOUND);
        assertThat(classify(new SQLException("ORA-12514", "66000", 12514)))
                .isEqualTo(ConnectionFailureReason.DATABASE_NOT_FOUND);
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(classify(new SQLException("syntax error", "42601")))
                .isEqualTo(ConnectionFailureReason.UNKNOWN);
        assertThat(classify(new SQLException((String) null)))
                .isEqualTo(ConnectionFailureReason.UNKNOWN);
    }

    @Test
    void aSelfReferencingCauseDoesNotLoop() {
        var failure = new SQLException("odd", "42000") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(classify(failure)).isEqualTo(ConnectionFailureReason.UNKNOWN);
    }
}
