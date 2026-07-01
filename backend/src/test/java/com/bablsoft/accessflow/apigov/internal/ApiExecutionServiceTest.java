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

    private ApiExecutionService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApiExecutionService(requestRepository, connectorRepository, permissionResolver,
                encryptionService, authApplier, oauth2TokenService, executor, responseMasker,
                maskingResolutionService, stateService,
                eventPublisher, JsonMapper.builder().build(),
                new ApigovRequestProperties(5_242_880L, 8_000_000L, 65_536L));
        lenient().when(maskingResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(java.util.List.of());
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
        var perm = new ResolvedApiConnectorPermission(connectorId, userId, true, false, false,
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
}
