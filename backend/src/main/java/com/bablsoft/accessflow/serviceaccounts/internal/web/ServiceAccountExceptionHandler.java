package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountBootstrapManagedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNameConflictException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyRevokedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountOwnerInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountUnknownMcpToolException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps the serviceaccounts module's exceptions to RFC 9457 {@code ProblemDetail}. Highest
 * precedence, or the security module's {@code Exception} catch-all wins the resolution race. Core
 * and security exceptions that pass through the controller ({@code EMAIL_ALREADY_EXISTS},
 * {@code QUOTA_EXCEEDED}, {@code ROLE_NOT_FOUND}) keep their global mapping.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class ServiceAccountExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(ServiceAccountNotFoundException.class)
    ProblemDetail handleNotFound(ServiceAccountNotFoundException ex) {
        var pd = problem(HttpStatus.NOT_FOUND, "error.service_account_not_found", null, "SERVICE_ACCOUNT_NOT_FOUND");
        pd.setProperty("serviceAccountId", ex.serviceAccountId().toString());
        return pd;
    }

    @ExceptionHandler(ServiceAccountKeyNotFoundException.class)
    ProblemDetail handleKeyNotFound(ServiceAccountKeyNotFoundException ex) {
        var pd = problem(HttpStatus.NOT_FOUND, "error.service_account_key_not_found", null,
                "SERVICE_ACCOUNT_KEY_NOT_FOUND");
        pd.setProperty("apiKeyId", ex.apiKeyId().toString());
        return pd;
    }

    @ExceptionHandler(ServiceAccountBootstrapManagedException.class)
    ProblemDetail handleBootstrapManaged(ServiceAccountBootstrapManagedException ex) {
        var pd = problem(HttpStatus.CONFLICT, "error.service_account_bootstrap_managed",
                new Object[] {ex.field()}, "SERVICE_ACCOUNT_BOOTSTRAP_MANAGED");
        pd.setProperty("field", ex.field());
        return pd;
    }

    @ExceptionHandler(ServiceAccountKeyBootstrapDeclaredException.class)
    ProblemDetail handleKeyBootstrapDeclared(ServiceAccountKeyBootstrapDeclaredException ex) {
        var pd = problem(HttpStatus.CONFLICT, "error.service_account_key_bootstrap_declared", null,
                "SERVICE_ACCOUNT_KEY_BOOTSTRAP_DECLARED");
        pd.setProperty("apiKeyId", ex.apiKeyId().toString());
        return pd;
    }

    @ExceptionHandler(ServiceAccountKeyRevokedException.class)
    ProblemDetail handleKeyRevoked(ServiceAccountKeyRevokedException ex) {
        var pd = problem(HttpStatus.CONFLICT, "error.service_account_key_revoked", null,
                "SERVICE_ACCOUNT_KEY_REVOKED");
        pd.setProperty("apiKeyId", ex.apiKeyId().toString());
        return pd;
    }

    @ExceptionHandler(ServiceAccountKeyNameConflictException.class)
    ProblemDetail handleKeyNameConflict(ServiceAccountKeyNameConflictException ex) {
        var pd = problem(HttpStatus.CONFLICT, "error.service_account_key_name_conflict",
                new Object[] {ex.name()}, "SERVICE_ACCOUNT_KEY_NAME_CONFLICT");
        pd.setProperty("name", ex.name());
        return pd;
    }

    @ExceptionHandler(ServiceAccountOwnerInvalidException.class)
    ProblemDetail handleOwnerInvalid(ServiceAccountOwnerInvalidException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "error.service_account_owner_invalid", null,
                "SERVICE_ACCOUNT_OWNER_INVALID");
        pd.setProperty("ownerUserId", ex.ownerUserId().toString());
        return pd;
    }

    @ExceptionHandler(ServiceAccountUnknownMcpToolException.class)
    ProblemDetail handleUnknownTool(ServiceAccountUnknownMcpToolException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "error.service_account_unknown_mcp_tool",
                new Object[] {ex.tool()}, "SERVICE_ACCOUNT_UNKNOWN_MCP_TOOL");
        pd.setProperty("tool", ex.tool());
        return pd;
    }

    private ProblemDetail problem(HttpStatus status, String key, Object[] args, String code) {
        var pd = ProblemDetail.forStatusAndDetail(status,
                messageSource.getMessage(key, args, LocaleContextHolder.getLocale()));
        pd.setProperty("error", code);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
