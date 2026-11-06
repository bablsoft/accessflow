package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotRefreshableException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionAlreadyAcceptedException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.DuplicateApiConnectorNameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void schemaNotRefreshableIs422() {
        var pd = handler.handleSchemaNotRefreshable(new ApiSchemaNotRefreshableException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_SCHEMA_NOT_REFRESHABLE");
    }

    @Test
    void schemaRevisionNotFoundIs404() {
        var pd = handler.handleSchemaRevisionNotFound(new ApiSchemaRevisionNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_SCHEMA_REVISION_NOT_FOUND");
    }

    @Test
    void schemaRevisionAlreadyAcceptedIs409() {
        var pd = handler.handleSchemaRevisionAlreadyAccepted(
                new ApiSchemaRevisionAlreadyAcceptedException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_SCHEMA_REVISION_ALREADY_ACCEPTED");
    }

    @Test
    void schemaDriftFindingNotFoundIs404() {
        var pd = handler.handleSchemaDriftFindingNotFound(
                new ApiSchemaDriftFindingNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_SCHEMA_DRIFT_FINDING_NOT_FOUND");
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
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("reason", "bad doc");
    }

    @Test
    void schemaFetchIs422WithReason() {
        var pd = handler.handleSchemaFetch(new ApiSchemaFetchException("404"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_SCHEMA_FETCH_ERROR");
        assertThat(pd.getProperties()).containsEntry("reason", "404");
    }

    @Test
    void connectionTestIs502WithReason() {
        var pd = handler.handleConnectionTest(new ApiConnectionTestException("refused"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(pd.getProperties()).containsEntry("reason", "refused");
    }

    @Test
    void reviewerNotEligibleIs403() {
        // Without this mapping the feature's own authorization check falls through to the global
        // catch-all and answers 500 with an ERROR-level log on every denial (#622).
        var pd = handler.handleReviewerNotEligible(
                new ApiReviewerNotEligibleException(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_REVIEWER_NOT_ELIGIBLE");
    }

    @Test
    void replayOperationUnavailableIs422WithOperationId() {
        var pd = handler.handleReplayOperationUnavailable(
                new com.bablsoft.accessflow.apigov.api.ApiReplayOperationUnavailableException("createCharge"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getDetail()).isEqualTo("error.api_replay_operation_unavailable");
        assertThat(pd.getProperties()).containsEntry("error", "API_REPLAY_OPERATION_UNAVAILABLE");
        assertThat(pd.getProperties()).containsEntry("operationId", "createCharge");
    }

    @Test
    void responseDiffNotAvailableIs404WithApiRequestId() {
        var id = java.util.UUID.randomUUID();
        var pd = handler.handleResponseDiffNotAvailable(
                new com.bablsoft.accessflow.apigov.api.ApiResponseDiffNotAvailableException(id));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getDetail()).isEqualTo("error.api_response_diff_not_available");
        assertThat(pd.getProperties()).containsEntry("error", "API_RESPONSE_DIFF_NOT_AVAILABLE");
        assertThat(pd.getProperties()).containsEntry("apiRequestId", id);
    }

    @Test
    void responseDiffIncompatibleIs422() {
        var pd = handler.handleResponseDiffIncompatible(
                new com.bablsoft.accessflow.apigov.api.ApiResponseDiffIncompatibleException(
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getDetail()).isEqualTo("error.api_response_diff_incompatible");
        assertThat(pd.getProperties()).containsEntry("error", "API_RESPONSE_DIFF_INCOMPATIBLE");
    }

    @Test
    void responseDiffUnsupportedIs422WithContentType() {
        var pd = handler.handleResponseDiffUnsupported(
                new com.bablsoft.accessflow.apigov.api.ApiResponseDiffUnsupportedException("text/csv"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getDetail()).isEqualTo("error.api_response_diff_unsupported");
        assertThat(pd.getProperties()).containsEntry("error", "API_RESPONSE_DIFF_UNSUPPORTED");
        assertThat(pd.getProperties()).containsEntry("contentType", "text/csv");
    }

    @Test
    void invalidDiffAgainstIs400() {
        var pd = handler.handleInvalidDiffAgainst(new InvalidDiffAgainstException("latest"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "VALIDATION_ERROR");
    }
}
