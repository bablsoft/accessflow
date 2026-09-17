package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountBootstrapManagedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNameConflictException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyRevokedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountOwnerInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountUnknownMcpToolException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountExceptionHandlerTest {

    private final ServiceAccountExceptionHandler handler = new ServiceAccountExceptionHandler(messageSource());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @Test
    void adviceRunsAtHighestPrecedenceSoTheSecurityCatchAllNeverWins() {
        var order = ServiceAccountExceptionHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void notFoundMapsTo404WithTheAccountId() {
        var id = UUID.randomUUID();
        var pd = handler.handleNotFound(new ServiceAccountNotFoundException(id));
        assertCommon(pd, HttpStatus.NOT_FOUND, "SERVICE_ACCOUNT_NOT_FOUND", "error.service_account_not_found");
        assertThat(pd.getProperties()).containsEntry("serviceAccountId", id.toString());
    }

    @Test
    void keyNotFoundMapsTo404WithTheKeyId() {
        var id = UUID.randomUUID();
        var pd = handler.handleKeyNotFound(new ServiceAccountKeyNotFoundException(id));
        assertCommon(pd, HttpStatus.NOT_FOUND, "SERVICE_ACCOUNT_KEY_NOT_FOUND", "error.service_account_key_not_found");
        assertThat(pd.getProperties()).containsEntry("apiKeyId", id.toString());
    }

    @Test
    void bootstrapManagedMapsTo409WithTheField() {
        var pd = handler.handleBootstrapManaged(new ServiceAccountBootstrapManagedException("display_name"));
        assertCommon(pd, HttpStatus.CONFLICT, "SERVICE_ACCOUNT_BOOTSTRAP_MANAGED",
                "error.service_account_bootstrap_managed");
        assertThat(pd.getProperties()).containsEntry("field", "display_name");
    }

    @Test
    void keyBootstrapDeclaredMapsTo409WithTheKeyId() {
        var id = UUID.randomUUID();
        var pd = handler.handleKeyBootstrapDeclared(new ServiceAccountKeyBootstrapDeclaredException(id));
        assertCommon(pd, HttpStatus.CONFLICT, "SERVICE_ACCOUNT_KEY_BOOTSTRAP_DECLARED",
                "error.service_account_key_bootstrap_declared");
        assertThat(pd.getProperties()).containsEntry("apiKeyId", id.toString());
    }

    @Test
    void keyRevokedMapsTo409() {
        var id = UUID.randomUUID();
        var pd = handler.handleKeyRevoked(new ServiceAccountKeyRevokedException(id));
        assertCommon(pd, HttpStatus.CONFLICT, "SERVICE_ACCOUNT_KEY_REVOKED", "error.service_account_key_revoked");
        assertThat(pd.getProperties()).containsEntry("apiKeyId", id.toString());
    }

    @Test
    void keyNameConflictMapsTo409WithTheName() {
        var pd = handler.handleKeyNameConflict(new ServiceAccountKeyNameConflictException("ci"));
        assertCommon(pd, HttpStatus.CONFLICT, "SERVICE_ACCOUNT_KEY_NAME_CONFLICT",
                "error.service_account_key_name_conflict");
        assertThat(pd.getProperties()).containsEntry("name", "ci");
    }

    @Test
    void ownerInvalidMapsTo422WithTheOwnerId() {
        var id = UUID.randomUUID();
        var pd = handler.handleOwnerInvalid(new ServiceAccountOwnerInvalidException(id));
        assertCommon(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SERVICE_ACCOUNT_OWNER_INVALID",
                "error.service_account_owner_invalid");
        assertThat(pd.getProperties()).containsEntry("ownerUserId", id.toString());
    }

    @Test
    void unknownToolMapsTo422WithTheTool() {
        var pd = handler.handleUnknownTool(new ServiceAccountUnknownMcpToolException("nope"));
        assertCommon(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SERVICE_ACCOUNT_UNKNOWN_MCP_TOOL",
                "error.service_account_unknown_mcp_tool");
        assertThat(pd.getProperties()).containsEntry("tool", "nope");
    }

    private static void assertCommon(ProblemDetail pd, HttpStatus status, String code, String key) {
        assertThat(pd.getStatus()).isEqualTo(status.value());
        assertThat(pd.getProperties()).containsEntry("error", code);
        assertThat(pd.getProperties()).containsKey("timestamp");
        assertThat(pd.getDetail()).isEqualTo(key);
    }
}
