package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.DuplicateApiConnectorNameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGovExceptionHandlerTest {

    private ApiGovExceptionHandler handler;

    @BeforeEach
    void setUp() {
        var messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(), any())).thenAnswer(i -> i.getArgument(0));
        handler = new ApiGovExceptionHandler(messageSource);
    }

    @Test
    void connectorNotFoundIs404() {
        var pd = handler.handleConnectorNotFound(new ApiConnectorNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_CONNECTOR_NOT_FOUND");
    }

    @Test
    void schemaNotFoundIs404() {
        var pd = handler.handleSchemaNotFound(new ApiSchemaNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void permissionNotFoundIs404() {
        var pd = handler.handlePermissionNotFound(new ApiConnectorPermissionNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void duplicateNameIs409() {
        var pd = handler.handleDuplicateName(new DuplicateApiConnectorNameException("x"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void schemaParseIs422WithReason() {
        var pd = handler.handleSchemaParse(new ApiSchemaParseException("bad doc"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties()).containsEntry("reason", "bad doc");
    }

    @Test
    void schemaFetchIs422WithReason() {
        var pd = handler.handleSchemaFetch(new ApiSchemaFetchException("404"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_SCHEMA_FETCH_ERROR");
        assertThat(pd.getProperties()).containsEntry("reason", "404");
    }

    @Test
    void connectionTestIs502WithReason() {
        var pd = handler.handleConnectionTest(new ApiConnectionTestException("refused"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(pd.getProperties()).containsEntry("reason", "refused");
    }
}
