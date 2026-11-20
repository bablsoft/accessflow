package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ConnectionFailureReason;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;
import java.util.Set;

/**
 * Maps a JDBC connection failure onto a {@link ConnectionFailureReason}, so the API can say what
 * went wrong without returning the driver's message (which names hosts, users and server
 * versions). The cause chain is the most reliable signal, then the SQLSTATE class, then vendor
 * error codes, and only last the wording of the message.
 */
final class ConnectionFailureClassifier {

    private static final int MAX_DEPTH = 16;

    /** MySQL/MariaDB 1045, SQL Server 18456, Oracle ORA-01017. */
    private static final Set<Integer> AUTHENTICATION_CODES = Set.of(1045, 18456, 1017);
    /** MySQL/MariaDB 1049, SQL Server 4060, Oracle ORA-12514 and ORA-12505. */
    private static final Set<Integer> DATABASE_NOT_FOUND_CODES = Set.of(1049, 4060, 12514, 12505);

    private ConnectionFailureClassifier() {
    }

    static ConnectionFailureReason classify(SQLException failure) {
        var fromCause = fromCauseChain(failure);
        if (fromCause != ConnectionFailureReason.UNKNOWN) {
            return fromCause;
        }
        var state = failure.getSQLState() == null ? "" : failure.getSQLState();
        var message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("ssl") || message.contains("tls") || message.contains("certif")) {
            return ConnectionFailureReason.SSL;
        }
        if (state.startsWith("28") || AUTHENTICATION_CODES.contains(failure.getErrorCode())) {
            return ConnectionFailureReason.AUTHENTICATION;
        }
        if ("3D000".equals(state) || DATABASE_NOT_FOUND_CODES.contains(failure.getErrorCode())) {
            return ConnectionFailureReason.DATABASE_NOT_FOUND;
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return ConnectionFailureReason.TIMEOUT;
        }
        if (state.startsWith("08")) {
            return ConnectionFailureReason.HOST_UNREACHABLE;
        }
        return ConnectionFailureReason.UNKNOWN;
    }

    private static ConnectionFailureReason fromCauseChain(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            switch (current) {
                case SSLException ignored -> {
                    return ConnectionFailureReason.SSL;
                }
                case SocketTimeoutException ignored -> {
                    return ConnectionFailureReason.TIMEOUT;
                }
                case SQLTimeoutException ignored -> {
                    return ConnectionFailureReason.TIMEOUT;
                }
                case UnknownHostException ignored -> {
                    return ConnectionFailureReason.HOST_UNREACHABLE;
                }
                case NoRouteToHostException ignored -> {
                    return ConnectionFailureReason.HOST_UNREACHABLE;
                }
                case ConnectException ignored -> {
                    return ConnectionFailureReason.HOST_UNREACHABLE;
                }
                default -> current = current.getCause() == current ? null : current.getCause();
            }
        }
        return ConnectionFailureReason.UNKNOWN;
    }
}
