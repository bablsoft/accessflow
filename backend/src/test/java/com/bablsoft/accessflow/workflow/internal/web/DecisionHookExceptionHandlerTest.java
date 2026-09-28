package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.DecisionHookNotFoundException;
import com.bablsoft.accessflow.workflow.api.DecisionHookScopeConflictException;
import com.bablsoft.accessflow.workflow.api.IllegalDecisionHookException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionHookExceptionHandlerTest {

    private final DecisionHookExceptionHandler handler =
            new DecisionHookExceptionHandler(messageSource());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @Test
    void notFoundMapsTo404() {
        var id = UUID.randomUUID();
        var pd = handler.handleNotFound(new DecisionHookNotFoundException(id));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "DECISION_HOOK_NOT_FOUND")
                .containsEntry("decisionHookId", id.toString()).containsKey("timestamp");
        assertThat(pd.getDetail()).isEqualTo("error.decision_hook_not_found");
    }

    @Test
    void aDefaultConflictMapsTo409WithoutADatasource() {
        var pd = handler.handleConflict(new DecisionHookScopeConflictException(null));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "DECISION_HOOK_SCOPE_CONFLICT")
                .doesNotContainKey("datasourceId");
        assertThat(pd.getDetail()).isEqualTo("error.decision_hook_default_conflict");
    }

    @Test
    void aDatasourceConflictNamesTheDatasource() {
        var id = UUID.randomUUID();
        var pd = handler.handleConflict(new DecisionHookScopeConflictException(id));
        assertThat(pd.getProperties()).containsEntry("datasourceId", id.toString());
        assertThat(pd.getDetail()).isEqualTo("error.decision_hook_datasource_conflict");
    }

    @Test
    void anIllegalHookMapsTo422WithTheResolvedKey() {
        var pd = handler.handleIllegal(
                new IllegalDecisionHookException("workflow.decision_hook.restricted_address"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("error", "DECISION_HOOK_INVALID");
        assertThat(pd.getDetail()).isEqualTo("workflow.decision_hook.restricted_address");
    }
}
