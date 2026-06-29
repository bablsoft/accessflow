package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallExecutor;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallResult;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorAuthApplier;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiExecutionServiceTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiConnectorUserPermissionRepository permissionRepository;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private ApiConnectorAuthApplier authApplier;
    @Mock private ConnectorOAuth2TokenService oauth2TokenService;
    @Mock private ApiCallExecutor executor;
    @Mock private ApiResponseMasker responseMasker;
    @Mock private ApiRequestStateService stateService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApiExecutionService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApiExecutionService(requestRepository, connectorRepository, permissionRepository,
                encryptionService, authApplier, oauth2TokenService, executor, responseMasker, stateService,
                eventPublisher, JsonMapper.builder().build());
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
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any())).thenReturn(new ApiCallResult(200, 12, 42, false, "{\"ssn\":\"x\"}"));
        var perm = new ApiConnectorUserPermissionEntity();
        perm.setRestrictedResponseFields(new String[]{"ssn"});
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.of(perm));
        when(responseMasker.mask(any(), any())).thenReturn("{\"ssn\":\"***\"}");

        var result = service.execute(requestId);

        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        assertThat(result.getResponseSnapshot()).contains("***");
        assertThat(result.getResponseBytes()).isEqualTo(42);
        verify(stateService).apply(entity, QueryStatus.EXECUTED);
    }

    @Test
    void executeFailureRecordsFailed() {
        var entity = approved();
        when(stateService.require(requestId)).thenReturn(entity);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector()));
        lenient().when(authApplier.authHeaders(any(), any(), anyInt())).thenReturn(java.util.Map.of());
        lenient().when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any())).thenThrow(new ApiExecutionException("boom"));

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
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any())).thenReturn(new ApiCallResult(204, 5, 0, false, ""));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any())).thenReturn("");

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
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any())).thenReturn(new ApiCallResult(200, 5, 2, false, "{}"));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any())).thenReturn("{}");

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
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any()))
                .thenReturn(new ApiCallResult(401, 5, 0, false, ""))
                .thenReturn(new ApiCallResult(200, 6, 2, false, "{}"));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any())).thenReturn("{}");

        var result = service.execute(requestId);

        verify(oauth2TokenService).evict(connectorId);
        verify(oauth2TokenService).fetchFresh(c);
        verify(executor, org.mockito.Mockito.times(2)).execute(any(), anyString(), anyString(), anyString(),
                any(), any(), anyInt(), anyLong(), any());
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
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any())).thenReturn(new ApiCallResult(401, 5, 0, false, ""));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any())).thenReturn("");

        var result = service.execute(requestId);

        verify(executor, org.mockito.Mockito.times(2)).execute(any(), anyString(), anyString(), anyString(),
                any(), any(), anyInt(), anyLong(), any());
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
        when(executor.execute(any(), anyString(), anyString(), anyString(), any(), any(), anyInt(),
                anyLong(), any())).thenReturn(new ApiCallResult(401, 5, 0, false, ""));
        when(permissionRepository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());
        when(responseMasker.mask(any(), any())).thenReturn("");

        service.execute(requestId);

        verify(oauth2TokenService, org.mockito.Mockito.never()).fetchFresh(any());
        verify(executor, org.mockito.Mockito.times(1)).execute(any(), anyString(), anyString(), anyString(),
                any(), any(), anyInt(), anyLong(), any());
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
}
