package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiConnectionTestResult;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorCommand;
import com.bablsoft.accessflow.apigov.api.DuplicateApiConnectorNameException;
import com.bablsoft.accessflow.apigov.api.GrantApiConnectorPermissionCommand;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorCommand;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorProber;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.MessageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiConnectorAdminServiceTest {

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiSchemaRepository schemaRepository;
    @Mock private ApiConnectorUserPermissionRepository permissionRepository;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private UserQueryService userQueryService;
    @Mock private ApiConnectorProber prober;
    @Mock private ConnectorOAuth2TokenService oauth2TokenService;
    @Mock private MessageSource messageSource;

    private DefaultApiConnectorAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiConnectorAdminService(connectorRepository, schemaRepository,
                permissionRepository, encryptionService, userQueryService, prober, oauth2TokenService,
                messageSource, JsonMapper.builder().build());
        lenient().when(schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.empty());
    }

    private CreateApiConnectorCommand createCommand() {
        return new CreateApiConnectorCommand(orgId, "Stripe", ApiProtocol.REST, "https://api.stripe.com",
                Map.of("X-Env", "test"), null, 5000, true, ApiAuthMethod.BEARER_TOKEN,
                Map.of("token", "sk_live_secret"),
                null, null, null, null, null, null, null, null, null, null,
                null, true, null, false, false, true, 2048L);
    }

    @Test
    void createEncryptsCredentialsAndPersists() {
        when(connectorRepository.existsByOrganizationIdAndName(orgId, "Stripe")).thenReturn(false);
        when(encryptionService.encrypt(any())).thenReturn("ENC");
        when(connectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.create(createCommand());

        assertThat(view.name()).isEqualTo("Stripe");
        assertThat(view.protocol()).isEqualTo(ApiProtocol.REST);
        assertThat(view.hasCredentials()).isTrue();
        assertThat(view.defaultHeaders()).containsEntry("X-Env", "test");
        verify(encryptionService).encrypt(any());
    }

    @Test
    void createRejectsDuplicateName() {
        when(connectorRepository.existsByOrganizationIdAndName(orgId, "Stripe")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createCommand()))
                .isInstanceOf(DuplicateApiConnectorNameException.class);
        verify(connectorRepository, never()).save(any());
    }

    @Test
    void createWithNoneAuthStoresNoCredentials() {
        var cmd = new CreateApiConnectorCommand(orgId, "Open", ApiProtocol.REST, "https://x", null, null,
                null, null, ApiAuthMethod.NONE, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        when(connectorRepository.existsByOrganizationIdAndName(orgId, "Open")).thenReturn(false);
        when(connectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.create(cmd);

        assertThat(view.hasCredentials()).isFalse();
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void listForAdminMapsPage() {
        var entity = persistedConnector();
        when(connectorRepository.findByOrganizationId(eq(orgId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(entity)));

        var page = service.listForAdmin(orgId, com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).name()).isEqualTo("Stripe");
    }

    @Test
    void listForUserReturnsOnlyGrantedActiveNonExpiredConnectors() {
        var entity = persistedConnector();
        var perm = new ApiConnectorUserPermissionEntity();
        perm.setConnectorId(entity.getId());
        perm.setExpiresAt(null);
        var expired = new ApiConnectorUserPermissionEntity();
        expired.setConnectorId(UUID.randomUUID());
        expired.setExpiresAt(java.time.Instant.now().minusSeconds(60));
        when(permissionRepository.findByUserId(userId)).thenReturn(List.of(perm, expired));
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));

        var page = service.listForUser(orgId, userId, com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id()).isEqualTo(entity.getId());
    }

    @Test
    void getForUserReturnsWhenPermissionGrantsRead() {
        var entity = persistedConnector();
        var perm = new ApiConnectorUserPermissionEntity();
        perm.setConnectorId(entity.getId());
        perm.setUserId(userId);
        perm.setCanRead(true);
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(permissionRepository.findByConnectorIdAndUserId(entity.getId(), userId)).thenReturn(Optional.of(perm));

        assertThat(service.getForUser(entity.getId(), orgId, userId).id()).isEqualTo(entity.getId());
    }

    @Test
    void getForUserThrowsWhenNoPermission() {
        var entity = persistedConnector();
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(permissionRepository.findByConnectorIdAndUserId(entity.getId(), userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForUser(entity.getId(), orgId, userId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void getForUserThrowsWhenPermissionGrantsNeitherReadNorWrite() {
        var entity = persistedConnector();
        var perm = new ApiConnectorUserPermissionEntity();
        perm.setConnectorId(entity.getId());
        perm.setUserId(userId);
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(permissionRepository.findByConnectorIdAndUserId(entity.getId(), userId)).thenReturn(Optional.of(perm));

        assertThatThrownBy(() -> service.getForUser(entity.getId(), orgId, userId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void getForAdminThrowsWhenMissing() {
        var id = UUID.randomUUID();
        when(connectorRepository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForAdmin(id, orgId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void updateChangesFieldsAndChecksNameCollision() {
        var entity = persistedConnector();
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(connectorRepository.existsByOrganizationIdAndName(orgId, "Renamed")).thenReturn(false);
        when(connectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.update(entity.getId(), orgId, new UpdateApiConnectorCommand("Renamed",
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, false));

        assertThat(view.name()).isEqualTo("Renamed");
        assertThat(view.active()).isFalse();
    }

    @Test
    void deleteRemovesConnectorAndEvictsToken() {
        var entity = persistedConnector();
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));

        service.delete(entity.getId(), orgId);

        verify(connectorRepository).delete(entity);
        verify(oauth2TokenService).evict(entity.getId());
    }

    @Test
    void createEncryptsOauth2SecretsAndExposesNonSecretConfig() {
        var cmd = new CreateApiConnectorCommand(orgId, "Okta API", ApiProtocol.REST, "https://api",
                null, null, null, null, ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS, null,
                "https://idp/token", "client-1", "secret-1", "read write", "aud-1", null, null, null,
                Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_POST,
                null, true, null, false, false, true, 2048L);
        when(connectorRepository.existsByOrganizationIdAndName(orgId, "Okta API")).thenReturn(false);
        when(encryptionService.encrypt("secret-1")).thenReturn("ENC-SECRET");
        when(connectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.create(cmd);

        assertThat(view.oauth2TokenUri()).isEqualTo("https://idp/token");
        assertThat(view.oauth2ClientId()).isEqualTo("client-1");
        assertThat(view.oauth2Scopes()).isEqualTo("read write");
        assertThat(view.oauth2Audience()).isEqualTo("aud-1");
        assertThat(view.oauth2GrantType()).isEqualTo(Oauth2GrantType.CLIENT_CREDENTIALS);
        assertThat(view.oauth2ClientAuth()).isEqualTo(Oauth2ClientAuth.CLIENT_SECRET_POST);
        assertThat(view.oauth2ClientSecretConfigured()).isTrue();
        assertThat(view.oauth2RefreshTokenConfigured()).isFalse();
        assertThat(view.oauth2PasswordConfigured()).isFalse();
        verify(encryptionService).encrypt("secret-1");
    }

    @Test
    void updateReEncryptsOauth2SecretAndEvictsToken() {
        var entity = persistedConnector();
        entity.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt("new-secret")).thenReturn("ENC2");
        when(connectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var cmd = new UpdateApiConnectorCommand(null, null, null, null, null, null, null, null,
                null, null, "new-secret", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        service.update(entity.getId(), orgId, cmd);

        verify(encryptionService).encrypt("new-secret");
        verify(oauth2TokenService).evict(entity.getId());
    }

    @Test
    void updateLeavesOauth2SecretUnchangedWhenNull() {
        var entity = persistedConnector();
        entity.setOauth2ClientSecretEncrypted("EXISTING");
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(connectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var cmd = new UpdateApiConnectorCommand(null, null, null, null, null, null, null, null,
                "https://idp/new", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        service.update(entity.getId(), orgId, cmd);

        assertThat(entity.getOauth2ClientSecretEncrypted()).isEqualTo("EXISTING");
        assertThat(entity.getOauth2TokenUri()).isEqualTo("https://idp/new");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void testOauth2ExercisesFetchFreshThenProbes() {
        var entity = persistedConnector();
        entity.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(oauth2TokenService.fetchFresh(entity)).thenReturn("tok");
        when(prober.probe(any(), any(), anyInt())).thenReturn(new ApiConnectionTestResult(true, "HTTP 200"));

        var result = service.test(entity.getId(), orgId);

        assertThat(result.success()).isTrue();
        verify(oauth2TokenService).fetchFresh(entity);
    }

    @Test
    void testOauth2FailureReturnsFailedResult() {
        var entity = persistedConnector();
        entity.setAuthMethod(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(oauth2TokenService.fetchFresh(entity)).thenThrow(new ApiExecutionException("bad token"));
        when(messageSource.getMessage(eq("apigov.test.oauth2_failed"), any(), any()))
                .thenReturn("OAuth2 token fetch failed: bad token");

        var result = service.test(entity.getId(), orgId);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("bad token");
        verify(prober, never()).probe(any(), any(), anyInt());
    }

    @Test
    void testDelegatesToProber() {
        var entity = persistedConnector();
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(prober.probe(ApiProtocol.REST, entity.getBaseUrl(), entity.getTimeoutMs()))
                .thenReturn(new ApiConnectionTestResult(true, "HTTP 200"));

        var result = service.test(entity.getId(), orgId);

        assertThat(result.success()).isTrue();
    }

    @Test
    void grantPermissionValidatesTargetUserInOrg() {
        var entity = persistedConnector();
        var userId = UUID.randomUUID();
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(userView(userId, orgId)));
        when(permissionRepository.findByConnectorIdAndUserId(entity.getId(), userId)).thenReturn(Optional.empty());
        when(permissionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.grantPermission(entity.getId(), orgId, UUID.randomUUID(),
                new GrantApiConnectorPermissionCommand(userId, true, false, false, null,
                        List.of("listPets"), List.of("data.ssn")));

        assertThat(view.canRead()).isTrue();
        assertThat(view.allowedOperations()).containsExactly("listPets");
        assertThat(view.restrictedResponseFields()).containsExactly("data.ssn");
    }

    @Test
    void grantPermissionRejectsUserFromAnotherOrg() {
        var entity = persistedConnector();
        var userId = UUID.randomUUID();
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(userView(userId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.grantPermission(entity.getId(), orgId, UUID.randomUUID(),
                new GrantApiConnectorPermissionCommand(userId, true, false, false, null, null, null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void revokePermissionRejectsForeignPermission() {
        var entity = persistedConnector();
        var permId = UUID.randomUUID();
        var foreign = new ApiConnectorUserPermissionEntity();
        foreign.setId(permId);
        foreign.setConnectorId(UUID.randomUUID());
        when(connectorRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(permissionRepository.findById(permId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revokePermission(entity.getId(), orgId, permId))
                .isInstanceOf(ApiConnectorPermissionNotFoundException.class);
    }

    private ApiConnectorEntity persistedConnector() {
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setName("Stripe");
        entity.setProtocol(ApiProtocol.REST);
        entity.setBaseUrl("https://api.stripe.com");
        entity.setAuthMethod(ApiAuthMethod.NONE);
        entity.setTimeoutMs(5000);
        return entity;
    }

    private static UserView userView(UUID id, UUID orgId) {
        return new UserView(id, "u@example.com", "User", UserRoleType.ANALYST, orgId, true,
                AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }
}
