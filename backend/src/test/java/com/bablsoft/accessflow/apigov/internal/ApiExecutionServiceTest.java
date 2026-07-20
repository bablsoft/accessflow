package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallExecutor;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallRequest;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallResult;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorAuthApplier;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiInlineExecutionService;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiExecutionServiceTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private ApiConnectorAuthApplier authApplier;
    @Mock private ConnectorOAuth2TokenService oauth2TokenService;
    @Mock private ApiCallExecutor executor;
    @Mock private ApiResponseMasker responseMasker;
    @Mock private com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService maskingResolutionService;
    @Mock private ApiRequestStateService stateService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Mock private com.bablsoft.accessflow.apigov.api.ApiConnectorVariableResolutionService
            variableResolutionService;

    private ApiExecutionService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApiExecutionService(requestRepository, connectorRepository, permissionResolver,
                encryptionService, authApplier, oauth2TokenService, executor, responseMasker,
                maskingResolutionService, stateService,
                eventPublisher, JsonMapper.builder().build(), variableResolutionService,
                new ApigovRequestProperties(5_242_880L, 8_000_000L, 65_536L, 8192));
        lenient().when(maskingResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(java.util.List.of());
        // No connector variables configured: the resolver is a no-op and the composed call is
        // handed to the executor unchanged.
        lenient().when(variableResolutionService.resolve(any(), any(), any(), any()))
                .thenReturn(com.bablsoft.accessflow.apigov.api.ResolvedApiVariables.empty());
    }

    private ApiRequestEntity approved() {
        var e = new ApiRequestEntity();
        e.setId(requestId);
        e.setConnectorId(connectorId);
        e.setSubmittedBy(userId);
        e.setVerb("GET");
        e.setRequestPath("/data");
        e.setStatus(QueryStatus.APPROVED);
        return e;
    }

    private ApiConnectorEntity connector() {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setProtocol(ApiProtocol.REST);
        c.setBaseUrl("https://api.test");
        c.setTimeoutMs(5000);
        c.setMaxResponseBytes(1_000_000);
        return c;
    }

    @Test
    void executeSuccessMasksResponseAndRecordsExecuted() {
        var entity = approved();
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        when(executor.execute(any(ApiCallRequest.class))).thenReturn(new ApiCallResult(200, 12, 42, false, "{\"ssn\":\"x\"}", "application/json"));
        var perm = new ResolvedApiConnectorPermission(connectorId, userId, true, false, false, false,
                java.util.List.of(), java.util.List.of("ssn"), null);
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.of(perm));
        when(responseMasker.mask(any(), any(), any())).thenReturn("{\"ssn\":\"***\"}");

        var result = service.execute(requestId);

        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        assertThat(result.getResponseSnapshot()).contains("***");
        assertThat(result.getResponseBytes()).isEqualTo(42);
        verify(stateService).apply(entity, QueryStatus.EXECUTED);
    }

    @Test
    void executeInjectsTraceparentAndStoresResponseContentType() {
        var entity = approved();
        entity.setTraceId("0af7651916cd43dd8448eb211c80319c");
        entity.setSpanId("b7ad6b7169203331");
        var c = connector();
        c.setTraceHeaderMapping("{\"traceparent\":\"traceparent\"}");
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        var captor = org.mockito.ArgumentCaptor.forClass(ApiCallRequest.class);
        when(executor.execute(captor.capture()))
                .thenReturn(new ApiCallResult(200, 5, 2, false, "{}", "application/json"));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("{}");

        var result = service.execute(requestId);

        assertThat(captor.getValue().headers()).containsEntry("traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(result.getResponseContentType()).isEqualTo("application/json");
    }

    @Test
    void executeClampsResponseCapToSystemCeiling() {
        var entity = approved();
        var c = connector();
        c.setMaxResponseBytes(50_000_000L); // above the 8 MB ceiling configured in setUp
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        var captor = org.mockito.ArgumentCaptor.forClass(ApiCallRequest.class);
        when(executor.execute(captor.capture()))
                .thenReturn(new ApiCallResult(200, 5, 2, false, "{}", "application/json"));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("{}");

        service.execute(requestId);

        assertThat(captor.getValue().maxResponseBytes()).isEqualTo(8_000_000L);
    }

    @Test
    void executeFailureRecordsFailed() {
        var entity = approved();
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        lenient().when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        lenient().when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(executor.execute(any(ApiCallRequest.class))).thenThrow(new ApiExecutionException("boom"));

        var result = service.execute(requestId);

        assertThat(result.getErrorMessage()).isEqualTo("boom");
        verify(stateService).apply(entity, QueryStatus.FAILED);
    }

    @Test
    void executeRejectsNonApprovedRequest() {
        var entity = approved();
        entity.setStatus(QueryStatus.PENDING_REVIEW);
        when(stateService.require(requestId)).thenReturn(entity);

        assertThatThrownBy(() -> service.execute(requestId))
                .isInstanceOf(IllegalApiRequestStateException.class);
    }

    @Test
    void executeDecryptsCredentialsWhenPresent() {
        var entity = approved();
        var c = connector();
        c.setAuthCredentialsEncrypted("ENC");
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(encryptionService.decrypt("ENC")).thenReturn("{\"token\":\"t\"}");
        when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of("Authorization", "Bearer t"));
        when(executor.execute(any(ApiCallRequest.class))).thenReturn(new ApiCallResult(204, 5, 0, false, "", null));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("");

        service.execute(requestId);

        verify(encryptionService).decrypt("ENC");
        verify(stateService).apply(entity, QueryStatus.EXECUTED);
    }

    @Test
    void oauth2InjectsBearerFromTokenServiceWithoutCallingApplier() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(oauth2TokenService.accessToken(c)).thenReturn("tok-1");
        when(executor.execute(any(ApiCallRequest.class))).thenReturn(new ApiCallResult(200, 5, 2, false, "{}", null));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("{}");

        service.execute(requestId);

        verify(oauth2TokenService).accessToken(c);
        verify(authApplier, org.mockito.Mockito.never()).authHeaders(any(), any(), anyInt());
        verify(stateService).apply(entity, QueryStatus.EXECUTED);
    }

    @Test
    void oauth2Upstream401EvictsRefreshesAndRetriesOnce() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(oauth2TokenService.accessToken(c)).thenReturn("stale");
        when(oauth2TokenService.fetchFresh(c)).thenReturn("fresh");
        when(executor.execute(any(ApiCallRequest.class)))
                .thenReturn(new ApiCallResult(401, 5, 0, false, "", null))
                .thenReturn(new ApiCallResult(200, 6, 2, false, "{}", null));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("{}");

        var result = service.execute(requestId);

        verify(oauth2TokenService).evict(connectorId);
        verify(oauth2TokenService).fetchFresh(c);
        verify(executor, org.mockito.Mockito.times(2)).execute(any(ApiCallRequest.class));
        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        verify(stateService).apply(entity, QueryStatus.EXECUTED);
    }

    @Test
    void oauth2SecondConsecutive401RecordsFailedWithoutThirdCall() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(oauth2TokenService.accessToken(c)).thenReturn("stale");
        when(oauth2TokenService.fetchFresh(c)).thenReturn("fresh");
        when(executor.execute(any(ApiCallRequest.class))).thenReturn(new ApiCallResult(401, 5, 0, false, "", null));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("");

        var result = service.execute(requestId);

        verify(executor, org.mockito.Mockito.times(2)).execute(any(ApiCallRequest.class));
        assertThat(result.getResponseStatusCode()).isEqualTo(401);
        verify(stateService).apply(entity, QueryStatus.EXECUTED);
    }

    @Test
    void nonOauth2_401IsNotRetried() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.BEARER_TOKEN);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        when(executor.execute(any(ApiCallRequest.class))).thenReturn(new ApiCallResult(401, 5, 0, false, "", null));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("");

        service.execute(requestId);

        verify(oauth2TokenService, org.mockito.Mockito.never()).fetchFresh(any());
        verify(executor, org.mockito.Mockito.times(1)).execute(any(ApiCallRequest.class));
    }

    @Test
    void oauth2TokenFetchFailureRecordsFailed() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(oauth2TokenService.accessToken(c)).thenThrow(new ApiExecutionException("token boom"));

        var result = service.execute(requestId);

        assertThat(result.getErrorMessage()).isEqualTo("token boom");
        verify(stateService).apply(entity, QueryStatus.FAILED);
    }

    @Test
    void executeInlineRunsCallAndMasksWithoutPersisting() {
        var orgId = UUID.randomUUID();
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector()));
        when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        when(executor.execute(any(ApiCallRequest.class)))
                .thenReturn(new ApiCallResult(200, 9, 5, false, "{\"ssn\":\"x\"}", "application/json"));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any(), any())).thenReturn("{\"ssn\":\"***\"}");

        var result = service.executeInline(new ApiInlineExecutionService.ApiInlineExecutionCommand(
                connectorId, orgId, userId, "op", "GET", "/x", null, null, ApiBodyType.RAW, null, null,
                null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.responseSnapshot()).contains("***");
        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }

    @Test
    void executeInlineReturnsFailureWhenConnectorMissing() {
        var orgId = UUID.randomUUID();
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.executeInline(new ApiInlineExecutionService.ApiInlineExecutionCommand(
                                connectorId, orgId, userId, null, "GET", "/x", null, null,
                                ApiBodyType.RAW, null, null, null, null)))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiExecutionException.class);
    }

    // --- AF-613: dynamic variables ------------------------------------------------------------

    @Test
    void resolvesVariablesAgainstTheFullyBuiltHeaderSetIncludingAuth() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.API_KEY);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(authApplier.authHeaders(any(), any(), anyInt()))
                .thenReturn(java.util.Map.of("Authorization", "Basic token_value"));
        lenient().when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(executor.execute(any(ApiCallRequest.class)))
                .thenReturn(new ApiCallResult(200, 5, 2, false, "{}", "application/json"));

        service.execute(requestId);

        // The context must carry the resolved auth header, or a vendor scheme that signs it cannot work.
        var context = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.apigov.api.ApiVariableRequestContext.class);
        verify(variableResolutionService).resolve(any(), org.mockito.ArgumentMatchers.eq(connectorId),
                context.capture(), any());
        assertThat(context.getValue().headers()).containsEntry("Authorization", "Basic token_value");
        assertThat(context.getValue().method()).isEqualTo("GET");
        assertThat(context.getValue().path()).isEqualTo("/data");
    }

    @Test
    void substitutesResolvedVariablesIntoTheOutboundCall() {
        var entity = approved();
        entity.setRequestPath("/data/{{tenant}}");
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        lenient().when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        lenient().when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(variableResolutionService.resolve(any(), any(), any(), any())).thenReturn(
                new com.bablsoft.accessflow.apigov.api.ResolvedApiVariables(
                        java.util.Map.of("tenant", "acme"), java.util.List.of()));
        when(executor.execute(any(ApiCallRequest.class)))
                .thenReturn(new ApiCallResult(200, 5, 2, false, "{}", "application/json"));

        service.execute(requestId);

        var sent = org.mockito.ArgumentCaptor.forClass(ApiCallRequest.class);
        verify(executor).execute(sent.capture());
        assertThat(sent.getValue().path()).isEqualTo("/data/acme");
    }

    @Test
    void passesThePersistedOverridesToTheResolver() {
        var entity = approved();
        entity.setVariableOverrides("{\"nonce\":\"fixed\"}");
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        lenient().when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        lenient().when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(executor.execute(any(ApiCallRequest.class)))
                .thenReturn(new ApiCallResult(200, 5, 2, false, "{}", "application/json"));

        service.execute(requestId);

        verify(variableResolutionService).resolve(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("nonce", "fixed")));
    }

    /**
     * The 401 retry rebuilds headers with the fresh bearer, so variables must be resolved again: a
     * signature computed over the stale token would simply fail a second time, and a nonce must not
     * be replayed.
     */
    @Test
    void oauth2RetryReResolvesVariablesAgainstTheRefreshedToken() {
        var entity = approved();
        var c = connector();
        c.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
        when(oauth2TokenService.accessToken(c)).thenReturn("stale");
        when(oauth2TokenService.fetchFresh(c)).thenReturn("fresh");
        when(executor.execute(any(ApiCallRequest.class)))
                .thenReturn(new ApiCallResult(401, 5, 0, false, "", null))
                .thenReturn(new ApiCallResult(200, 5, 2, false, "{}", "application/json"));

        service.execute(requestId);

        var contexts = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.apigov.api.ApiVariableRequestContext.class);
        verify(variableResolutionService, org.mockito.Mockito.times(2))
                .resolve(any(), any(), contexts.capture(), any());
        assertThat(contexts.getAllValues().get(0).headers()).containsEntry("Authorization", "Bearer stale");
        assertThat(contexts.getAllValues().get(1).headers()).containsEntry("Authorization", "Bearer fresh");
    }

    /**
     * api_requests.error_message is persisted and shown to reviewers, and the JDK's IOException
     * message can embed the substituted URI. Every resolved value must be scrubbed before it escapes.
     */
    @Test
    void redactsResolvedValuesFromAPersistedFailureMessage() {
        var entity = approved();
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        lenient().when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        lenient().when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(variableResolutionService.resolve(any(), any(), any(), any())).thenReturn(
                new com.bablsoft.accessflow.apigov.api.ResolvedApiVariables(
                        java.util.Map.of("sig", "s3cr3tdigest"), java.util.List.of()));
        when(executor.execute(any(ApiCallRequest.class))).thenThrow(new ApiExecutionException(
                "API call failed: connect to https://api.test/data?sig=s3cr3tdigest"));

        var result = service.execute(requestId);

        assertThat(result.getErrorMessage()).doesNotContain("s3cr3tdigest").contains("***");
        verify(stateService).apply(entity, QueryStatus.FAILED);
    }

    @Test
    void redactLeavesMessagesWithoutResolvedValuesUntouched() {
        var resolved = new com.bablsoft.accessflow.apigov.api.ResolvedApiVariables(
                java.util.Map.of("a", "abc", "blank", ""), java.util.List.of());

        assertThat(ApiExecutionService.redact("plain failure", resolved)).isEqualTo("plain failure");
        assertThat(ApiExecutionService.redact(null, resolved)).isNull();
        assertThat(ApiExecutionService.redact("x", null)).isEqualTo("x");
        // A blank resolved value must not turn every character into ***.
        assertThat(ApiExecutionService.redact("hi", resolved)).isEqualTo("hi");
    }
}
