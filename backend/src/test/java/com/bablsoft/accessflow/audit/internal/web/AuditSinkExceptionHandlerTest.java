package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditSinkConfigException;
import com.bablsoft.accessflow.audit.api.AuditSinkNameConflictException;
import com.bablsoft.accessflow.audit.api.AuditSinkNotFoundException;
import com.bablsoft.accessflow.audit.api.AuditSinkTestFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditSinkExceptionHandlerTest {

    @Mock MessageSource messageSource;

    private AuditSinkExceptionHandler handler;

    @BeforeEach
    void setUp() {
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        handler = new AuditSinkExceptionHandler(messageSource);
    }

    @Test
    void notFoundMapsTo404() {
        var pd = handler.handleNotFound(new AuditSinkNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "AUDIT_SINK_NOT_FOUND");
        assertThat(pd.getDetail()).isEqualTo("error.audit_sink_not_found");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    void nameConflictMapsTo409() {
        var pd = handler.handleNameConflict(new AuditSinkNameConflictException("dup"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error", "AUDIT_SINK_NAME_EXISTS");
        assertThat(pd.getDetail()).isEqualTo("error.audit_sink_name_exists");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    void configMapsTo422() {
        var pd = handler.handleConfig(new AuditSinkConfigException("bad"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "AUDIT_SINK_CONFIG_INVALID");
        assertThat(pd.getDetail()).isEqualTo("error.audit_sink_config_invalid");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    void testFailedMapsTo502() {
        var pd = handler.handleTestFailed(new AuditSinkTestFailedException("down", null));

        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("error", "AUDIT_SINK_TEST_FAILED");
        assertThat(pd.getDetail()).isEqualTo("error.audit_sink_test_failed");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }
}
