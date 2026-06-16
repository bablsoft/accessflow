package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.QueryTemplateAccessDeniedException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNameAlreadyExistsException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionNotFoundException;
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
class QueryTemplateExceptionHandlerTest {

    @Mock MessageSource messageSource;

    private QueryTemplateExceptionHandler handler;

    @BeforeEach
    void setUp() {
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        handler = new QueryTemplateExceptionHandler(messageSource);
    }

    @Test
    void notFoundMapsTo404() {
        var id = UUID.randomUUID();
        var pd = handler.handleNotFound(new QueryTemplateNotFoundException(id));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_TEMPLATE_NOT_FOUND");
        assertThat(pd.getProperties()).containsEntry("templateId", id.toString());
        assertThat(pd.getDetail()).isEqualTo("error.query_template_not_found");
    }

    @Test
    void accessDeniedMapsTo403() {
        var id = UUID.randomUUID();
        var pd = handler.handleAccessDenied(new QueryTemplateAccessDeniedException(id));

        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_TEMPLATE_FORBIDDEN");
        assertThat(pd.getProperties()).containsEntry("templateId", id.toString());
        assertThat(pd.getDetail()).isEqualTo("error.query_template_access_denied");
    }

    @Test
    void versionNotFoundMapsTo404() {
        var templateId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var pd = handler.handleVersionNotFound(
                new QueryTemplateVersionNotFoundException(templateId, versionId));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_TEMPLATE_VERSION_NOT_FOUND");
        assertThat(pd.getProperties()).containsEntry("templateId", templateId.toString());
        assertThat(pd.getProperties()).containsEntry("versionId", versionId.toString());
        assertThat(pd.getDetail()).isEqualTo("error.query_template_version_not_found");
    }

    @Test
    void nameConflictMapsTo409() {
        var pd = handler.handleNameConflict(new QueryTemplateNameAlreadyExistsException("Top"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_TEMPLATE_NAME_CONFLICT");
        assertThat(pd.getProperties()).containsEntry("name", "Top");
        assertThat(pd.getDetail()).isEqualTo("error.query_template_name_conflict");
    }
}
