package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableLookupService;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiReplayOperationUnavailableException;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiRequestReplayService.ReplayCommand;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.apigov.api.ApiRequestSubmissionResult;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiRequestReplayServiceTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiSchemaService schemaService;
    @Mock private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock private ApiConnectorVariableLookupService variableLookupService;
    @Mock private ApiRequestService requestService;
    @Mock private MessageSource messageSource;

    private DefaultApiRequestReplayService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID newId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiRequestReplayService(requestRepository, schemaService, permissionResolver,
                variableLookupService, requestService, new ApigovRequestProperties(5_242_880L, 10_485_760L, 65_536L, 8, 500),
                messageSource, JsonMapper.builder().build());
        lenient().when(messageSource.getMessage(eq("replay.api_justification"), any(), any()))
                .thenAnswer(i -> "Replay of API request " + ((Object[]) i.getArgument(1))[0]);
        lenient().when(requestService.submit(any()))
                .thenReturn(new ApiRequestSubmissionResult(newId, QueryStatus.PENDING_AI));
    }

    private ApiRequestEntity source(QueryStatus status) {
        var e = new ApiRequestEntity();
        e.setId(sourceId);
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setSubmittedBy(callerId);
        e.setOperationId("createCharge");
        e.setVerb("POST");
        e.setRequestPath("/charges");
        e.setRequestHeaders("{\"X-Trace\":\"abc\"}");
        e.setQueryParams("{\"limit\":\"10\"}");
        e.setBodyType(ApiBodyType.FORM_DATA);
        e.setRequestContentType("multipart/form-data");
        e.setRequestBody(null);
        e.setFormFields("[{\"key\":\"amount\",\"type\":\"TEXT\",\"value\":\"100\"}]");
        e.setBinaryFilename("ignored.bin");
        e.setVariableOverrides("{}");
        e.setStatus(status);
        e.setSubmissionReason(SubmissionReason.USER_SUBMITTED);
        e.setScheduledFor(Instant.parse("2026-01-01T00:00:00Z"));
        e.setJustification("original reason");
        e.setSubmittedIp("9.9.9.9");
        e.setSubmittedUserAgent("old-ua");
        return e;
    }

    private ReplayCommand command() {
        return new ReplayCommand(sourceId, orgId, callerId, false, "1.2.3.4", "ua", null);
    }

    private ResolvedApiConnectorPermission grant(boolean canOverride) {
        return new ResolvedApiConnectorPermission(connectorId, callerId, true, true, false, canOverride,
                List.of(), List.of(), null);
    }

    private void stub(ApiRequestEntity entity) {
        when(requestRepository.findByIdAndOrganizationId(sourceId, orgId)).thenReturn(Optional.of(entity));
    }

    private SubmitApiRequestCommand submitted() {
        var captor = ArgumentCaptor.forClass(SubmitApiRequestCommand.class);
        verify(requestService).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    void replayCopiesEveryStoredFieldOntoANewSubmission() {
        stub(source(QueryStatus.EXECUTED));
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of(
                new ApiOperation("createCharge", "POST", "/charges", null, true, null, null)));

        var result = service.replay(command());

        assertThat(result.newApiRequestId()).isEqualTo(newId);
        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
        assertThat(result.droppedVariableOverrideCount()).isZero();
        var cmd = submitted();
        assertThat(cmd.connectorId()).isEqualTo(connectorId);
        assertThat(cmd.organizationId()).isEqualTo(orgId);
        assertThat(cmd.submitterUserId()).isEqualTo(callerId);
        assertThat(cmd.admin()).isFalse();
        assertThat(cmd.operationId()).isEqualTo("createCharge");
        assertThat(cmd.verb()).isEqualTo("POST");
        assertThat(cmd.requestPath()).isEqualTo("/charges");
        assertThat(cmd.requestHeaders()).containsExactlyEntriesOf(Map.of("X-Trace", "abc"));
        assertThat(cmd.queryParams()).containsExactlyEntriesOf(Map.of("limit", "10"));
        assertThat(cmd.bodyType()).isEqualTo(ApiBodyType.FORM_DATA);
        assertThat(cmd.requestContentType()).isEqualTo("multipart/form-data");
        assertThat(cmd.requestBody()).isNull();
        assertThat(cmd.formFields()).containsExactly(
                new ApiFormField("amount", ApiFormField.ApiFormFieldType.TEXT, "100", null, null));
        assertThat(cmd.binaryFilename()).isEqualTo("ignored.bin");
        assertThat(cmd.variableOverrides()).isEmpty();
        assertThat(cmd.justification()).isEqualTo("Replay of API request " + sourceId);
        assertThat(cmd.scheduledFor()).isNull();
        assertThat(cmd.submissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
        assertThat(cmd.submittedIp()).isEqualTo("1.2.3.4");
        assertThat(cmd.submittedUserAgent()).isEqualTo("ua");
        assertThat(cmd.onBehalfOfUserId()).isNull();
        assertThat(cmd.replayOf().originalApiRequestId()).isEqualTo(sourceId);
        assertThat(cmd.replayOf().droppedVariableOverrideCount()).isZero();
        verifyNoInteractions(permissionResolver);
    }

    @Test
    void breakGlassSourceIsReplayedAsAnOrdinarySubmissionByTheCaller() {
        var src = source(QueryStatus.EXECUTED);
        src.setSubmissionReason(SubmissionReason.EMERGENCY_ACCESS);
        stub(src);
        var alice = UUID.randomUUID();

        service.replay(new ReplayCommand(sourceId, orgId, callerId, true, "1.2.3.4", "ua", alice));

        var cmd = submitted();
        assertThat(cmd.submitterUserId()).isEqualTo(callerId);
        assertThat(cmd.admin()).isTrue();
        assertThat(cmd.submissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
        assertThat(cmd.scheduledFor()).isNull();
        assertThat(cmd.onBehalfOfUserId()).isEqualTo(alice);
    }

    @ParameterizedTest
    @EnumSource(value = QueryStatus.class, names = {"EXECUTED", "FAILED", "REJECTED", "TIMED_OUT", "CANCELLED"})
    void everyTerminalStatusIsReplayable(QueryStatus status) {
        stub(source(status));

        service.replay(command());

        verify(requestService).submit(any());
    }

    @ParameterizedTest
    @EnumSource(value = QueryStatus.class, names = {"PENDING_AI", "PENDING_REVIEW", "APPROVED"})
    void nonTerminalSourceIsAnIllegalState(QueryStatus status) {
        stub(source(status));

        assertThatThrownBy(() -> service.replay(command()))
                .isInstanceOf(IllegalApiRequestStateException.class)
                .satisfies(ex -> assertThat(((IllegalApiRequestStateException) ex).currentStatus())
                        .isEqualTo(status));
        verify(requestService, never()).submit(any());
    }

    @Test
    void sourceInAnotherOrganizationIsNotFound() {
        when(requestRepository.findByIdAndOrganizationId(sourceId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(command()))
                .isInstanceOf(ApiRequestNotFoundException.class);
    }

    /** Not even an admin may replay another user's request: it would resend input they never saw. */
    @Test
    void someoneElsesSourceIsNotFoundEvenForAnAdmin() {
        var src = source(QueryStatus.EXECUTED);
        src.setSubmittedBy(otherUserId);
        stub(src);

        assertThatThrownBy(() -> service.replay(command()))
                .isInstanceOf(ApiRequestNotFoundException.class);
        assertThatThrownBy(() -> service.replay(
                new ReplayCommand(sourceId, orgId, callerId, true, "1.2.3.4", "ua", null)))
                .isInstanceOf(ApiRequestNotFoundException.class);
        verify(requestService, never()).submit(any());
    }

    @Test
    void operationFilteredOutOfTheCatalogIsUnavailable() {
        stub(source(QueryStatus.EXECUTED));
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of(
                new ApiOperation("listCharges", "GET", "/charges", null, false, null, null)));

        assertThatThrownBy(() -> service.replay(command()))
                .isInstanceOf(ApiReplayOperationUnavailableException.class)
                .satisfies(ex -> assertThat(((ApiReplayOperationUnavailableException) ex).operationId())
                        .isEqualTo("createCharge"));
        verify(requestService, never()).submit(any());
    }

    @Test
    void emptyCatalogPasses() {
        stub(source(QueryStatus.EXECUTED));
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of());

        service.replay(command());

        verify(requestService).submit(any());
    }

    @Test
    void freeFormSourceSkipsTheCatalog() {
        var src = source(QueryStatus.EXECUTED);
        src.setOperationId(null);
        stub(src);

        service.replay(command());

        verifyNoInteractions(schemaService);
        assertThat(submitted().operationId()).isNull();
    }

    @Test
    void overridesAllDroppedWithoutAGrant() {
        var src = source(QueryStatus.EXECUTED);
        src.setVariableOverrides("{\"tenant\":\"t1\",\"region\":\"eu\"}");
        stub(src);
        when(permissionResolver.resolve(connectorId, callerId)).thenReturn(Optional.empty());

        var result = service.replay(command());

        assertThat(result.droppedVariableOverrideCount()).isEqualTo(2);
        var cmd = submitted();
        assertThat(cmd.variableOverrides()).isEmpty();
        assertThat(cmd.replayOf().droppedVariableOverrideCount()).isEqualTo(2);
        verifyNoInteractions(variableLookupService);
    }

    @Test
    void overridesAllDroppedWithoutCanOverrideVariables() {
        var src = source(QueryStatus.EXECUTED);
        src.setVariableOverrides("{\"tenant\":\"t1\"}");
        stub(src);
        when(permissionResolver.resolve(connectorId, callerId)).thenReturn(Optional.of(grant(false)));

        var result = service.replay(command());

        assertThat(result.droppedVariableOverrideCount()).isEqualTo(1);
        assertThat(submitted().variableOverrides()).isEmpty();
    }

    @Test
    void overridesNoLongerOverridableAreDroppedAndCounted() {
        var src = source(QueryStatus.EXECUTED);
        src.setVariableOverrides("{\"tenant\":\"t1\",\"region\":\"eu\",\"tier\":\"gold\"}");
        stub(src);
        when(permissionResolver.resolve(connectorId, callerId)).thenReturn(Optional.of(grant(true)));
        when(variableLookupService.overridableNames(connectorId, orgId)).thenReturn(Set.of("tenant", "tier"));

        var result = service.replay(command());

        assertThat(result.droppedVariableOverrideCount()).isEqualTo(1);
        var cmd = submitted();
        assertThat(cmd.variableOverrides()).containsExactlyInAnyOrderEntriesOf(
                Map.of("tenant", "t1", "tier", "gold"));
        assertThat(cmd.replayOf().droppedVariableOverrideCount()).isEqualTo(1);
    }

    @Test
    void malformedStoredJsonIsReadAsEmpty() {
        var src = source(QueryStatus.EXECUTED);
        src.setOperationId(null);
        src.setRequestHeaders("not json");
        src.setQueryParams(null);
        src.setFormFields("{broken");
        src.setVariableOverrides(" ");
        stub(src);

        service.replay(command());

        var cmd = submitted();
        assertThat(cmd.requestHeaders()).isEmpty();
        assertThat(cmd.queryParams()).isEmpty();
        assertThat(cmd.formFields()).isEmpty();
        assertThat(cmd.variableOverrides()).isEmpty();
        verifyNoInteractions(permissionResolver);
    }

    @Test
    void blankFormFieldsAreReadAsEmpty() {
        var src = source(QueryStatus.EXECUTED);
        src.setOperationId(null);
        src.setFormFields(null);
        stub(src);

        service.replay(command());

        assertThat(submitted().formFields()).isEmpty();
    }

    @Test
    void overrideValuesThatNoLongerValidateAreDroppedAndCounted() {
        var src = source(QueryStatus.EXECUTED);
        // The limit in setUp is 8 bytes: "toolongvalue" exceeds it, "a\nb" carries a control character.
        src.setVariableOverrides("{\"tenant\":\"t1\",\"region\":\"toolongvalue\",\"tier\":\"a\\nb\"}");
        stub(src);
        when(permissionResolver.resolve(connectorId, callerId)).thenReturn(Optional.of(grant(true)));
        when(variableLookupService.overridableNames(connectorId, orgId))
                .thenReturn(Set.of("tenant", "region", "tier"));

        var result = service.replay(command());

        assertThat(result.droppedVariableOverrideCount()).isEqualTo(2);
        assertThat(submitted().variableOverrides()).containsExactlyEntriesOf(Map.of("tenant", "t1"));
    }
}
