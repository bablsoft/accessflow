package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UpdateUserCommand;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.ApiKeyView;
import com.bablsoft.accessflow.security.api.IssuedApiKey;
import com.bablsoft.accessflow.serviceaccounts.api.CreateServiceAccountCommand;
import com.bablsoft.accessflow.serviceaccounts.api.IssueServiceAccountKeyCommand;
import com.bablsoft.accessflow.serviceaccounts.api.RotateServiceAccountKeyCommand;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountBootstrapManagedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountClearableField;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNameConflictException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyRevokedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountOwnerInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountUnknownMcpToolException;
import com.bablsoft.accessflow.serviceaccounts.api.UpdateServiceAccountCommand;
import com.bablsoft.accessflow.serviceaccounts.internal.config.ServiceAccountsProperties;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultServiceAccountAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock ServiceAccountRepository repository;
    @Mock ServiceAccountProvisioningService provisioningService;
    @Mock UserAdminService userAdminService;
    @Mock ApiKeyService apiKeyService;
    @Mock PasswordEncoder passwordEncoder;

    private DefaultServiceAccountAdminService service;
    private UUID userId;
    private ServiceAccountEntity entity;

    @BeforeEach
    void setUp() {
        service = new DefaultServiceAccountAdminService(repository, provisioningService, userAdminService,
                apiKeyService, passwordEncoder, new ServiceAccountsProperties(Duration.ofHours(24)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        userId = UUID.randomUUID();
        entity = entity(userId, ServiceAccountSource.UI);
    }

    // ---- list / get -------------------------------------------------------------------------

    @Test
    void listPagesTheDetailRowsAndJoinsUsersAndKeys() {
        var other = entity(UUID.randomUUID(), ServiceAccountSource.BOOTSTRAP);
        when(repository.findAllByOrganizationId(eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity, other)));
        when(userAdminService.findByIds(ORG, List.of(userId, other.getUserId())))
                .thenReturn(Map.of(userId, user(userId), other.getUserId(), user(other.getUserId())));
        var live = key(userId, "live", null, null, false, Instant.parse("2026-09-16T00:00:00Z"));
        var expired = key(userId, "expired", NOW.minusSeconds(1), null, false, Instant.parse("2026-09-15T00:00:00Z"));
        var revoked = key(userId, "revoked", null, NOW.minusSeconds(5), false, null);
        when(apiKeyService.listByUserIds(List.of(userId, other.getUserId())))
                .thenReturn(Map.of(userId, List.of(live, expired, revoked)));

        var page = service.list(ORG, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(2);
        var first = page.content().get(0);
        assertThat(first.id()).isEqualTo(userId);
        assertThat(first.email()).isEqualTo(userId + "@example.com");
        assertThat(first.activeApiKeyCount()).isEqualTo(1);
        assertThat(first.lastUsedAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(first.apiKeys()).isEmpty();
        var second = page.content().get(1);
        assertThat(second.managedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
        assertThat(second.activeApiKeyCount()).isZero();
        assertThat(second.lastUsedAt()).isNull();
    }

    @Test
    void listFiltersByManagedByWhenGiven() {
        when(repository.findAllByOrganizationIdAndManagedBy(eq(ORG), eq(ServiceAccountSource.BOOTSTRAP),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(),
                org.springframework.data.domain.PageRequest.of(0, 20), 0));
        when(userAdminService.findByIds(ORG, List.of())).thenReturn(Map.of());
        when(apiKeyService.listByUserIds(List.of())).thenReturn(Map.of());

        var page = service.list(ORG, ServiceAccountSource.BOOTSTRAP, PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        verify(repository, never()).findAllByOrganizationId(any(), any());
    }

    @Test
    void listFailsLoudlyWhenTheUserRowIsGone() {
        when(repository.findAllByOrganizationId(eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));
        when(userAdminService.findByIds(ORG, List.of(userId))).thenReturn(Map.of());
        when(apiKeyService.listByUserIds(List.of(userId))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.list(ORG, null, PageRequest.of(0, 20)))
                .isInstanceOf(ServiceAccountNotFoundException.class);
    }

    @Test
    void getReturnsTheDetailWithKeysNewestFirstAsListed() {
        stubLoad();
        stubUser();
        var declared = key(userId, "terraform", null, null, true, null);
        var extra = key(userId, "extra", null, null, false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(extra, declared));

        var view = service.get(ORG, userId);

        assertThat(view.apiKeys()).extracting(k -> k.name()).containsExactly("extra", "terraform");
        assertThat(view.apiKeys().get(1).bootstrapDeclared()).isTrue();
        assertThat(view.activeApiKeyCount()).isEqualTo(2);
        assertThat(view.mcpToolAllowList()).containsExactly("validate_sql");
        assertThat(view.roleName()).isEqualTo("ANALYST");
    }

    @Test
    void getInAnotherOrganizationIsNotFound() {
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(ORG, userId))
                .isInstanceOf(ServiceAccountNotFoundException.class)
                .satisfies(ex -> assertThat(((ServiceAccountNotFoundException) ex).serviceAccountId())
                        .isEqualTo(userId));
    }

    // ---- create -----------------------------------------------------------------------------

    @Test
    void createMintsAnUnusablePasswordUserDefaultsToReadonlyAndRegistersAsUiManaged() {
        var created = user(userId);
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        when(userAdminService.createUser(any())).thenReturn(created);
        var fresh = entity(userId, ServiceAccountSource.UI);
        fresh.setDescription(null);
        fresh.setMcpToolAllowList(null);
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.of(fresh));
        when(repository.save(fresh)).thenReturn(fresh);
        when(userAdminService.findByIds(ORG, List.of(userId))).thenReturn(Map.of(userId, created));
        when(apiKeyService.list(userId)).thenReturn(List.of());

        var view = service.create(ORG, new CreateServiceAccountCommand("bot@example.com", "Bot", null, null,
                "nightly", null, List.of("list_datasources"), 60, null));

        var captor = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userAdminService).createUser(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG);
        assertThat(captor.getValue().email()).isEqualTo("bot@example.com");
        assertThat(captor.getValue().passwordHash()).isEqualTo("unusable-hash");
        assertThat(captor.getValue().role()).isEqualTo(UserRoleType.READONLY);
        assertThat(captor.getValue().roleId()).isNull();
        assertThat(captor.getValue().platformAdmin()).isFalse();
        verify(provisioningService).ensureRegistered(ORG, userId, ServiceAccountSource.UI);
        assertThat(fresh.getDescription()).isEqualTo("nightly");
        assertThat(fresh.getMcpToolAllowList()).containsExactly("list_datasources");
        assertThat(fresh.getRateLimitPerMinute()).isEqualTo(60);
        assertThat(fresh.getRateLimitPerDay()).isNull();
        assertThat(view.id()).isEqualTo(userId);
        assertThat(view.apiKeys()).isEmpty();
    }

    @Test
    void createHonoursAnExplicitRoleAndRoleId() {
        when(passwordEncoder.encode(anyString())).thenReturn("h");
        when(userAdminService.createUser(any())).thenReturn(user(userId));
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        stubUser();
        when(apiKeyService.list(userId)).thenReturn(List.of());
        var roleId = UUID.randomUUID();

        service.create(ORG, new CreateServiceAccountCommand("bot@example.com", "Bot", UserRoleType.ANALYST,
                roleId, null, null, null, null, null));

        var captor = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userAdminService).createUser(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRoleType.ANALYST);
        assertThat(captor.getValue().roleId()).isEqualTo(roleId);
        assertThat(entity.getMcpToolAllowList()).isNull();
    }

    @Test
    void createRejectsAnOwnerThatIsMissingInactiveOrNotHuman() {
        var owner = UUID.randomUUID();
        when(userAdminService.findByIds(ORG, List.of(owner))).thenReturn(Map.of());
        assertThatThrownBy(() -> service.create(ORG, command(owner, null)))
                .isInstanceOf(ServiceAccountOwnerInvalidException.class);

        when(userAdminService.findByIds(ORG, List.of(owner)))
                .thenReturn(Map.of(owner, user(owner, false, PrincipalType.HUMAN)));
        assertThatThrownBy(() -> service.create(ORG, command(owner, null)))
                .isInstanceOf(ServiceAccountOwnerInvalidException.class);

        when(userAdminService.findByIds(ORG, List.of(owner)))
                .thenReturn(Map.of(owner, user(owner, true, PrincipalType.SERVICE_ACCOUNT)));
        assertThatThrownBy(() -> service.create(ORG, command(owner, null)))
                .isInstanceOf(ServiceAccountOwnerInvalidException.class)
                .satisfies(ex -> assertThat(((ServiceAccountOwnerInvalidException) ex).ownerUserId())
                        .isEqualTo(owner));
        verify(userAdminService, never()).createUser(any());
        verifyNoInteractions(provisioningService);
    }

    @Test
    void createRejectsAnUnknownToolBeforeTouchingUsers() {
        assertThatThrownBy(() -> service.create(ORG, command(null, List.of("validate_sql", "drop_everything"))))
                .isInstanceOf(ServiceAccountUnknownMcpToolException.class)
                .satisfies(ex -> assertThat(((ServiceAccountUnknownMcpToolException) ex).tool())
                        .isEqualTo("drop_everything"));
        verify(userAdminService, never()).createUser(any());
    }

    // ---- update -----------------------------------------------------------------------------

    @Test
    void updateAppliesDeclaredFieldsThroughTheUserServiceAndOnlyTheUiOwnedFieldsSent() {
        stubLoad();
        stubUser();
        when(repository.save(entity)).thenReturn(entity);
        when(apiKeyService.list(userId)).thenReturn(List.of());
        var roleId = UUID.randomUUID();
        entity.setOwnerUserId(ACTOR);
        entity.setRateLimitPerMinute(5);

        service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand("Renamed", null, roleId, false,
                null, null, List.of(), null, 500, null));

        var captor = ArgumentCaptor.forClass(UpdateUserCommand.class);
        verify(userAdminService).updateUser(eq(userId), eq(ORG), eq(ACTOR), captor.capture());
        assertThat(captor.getValue().displayName()).isEqualTo("Renamed");
        assertThat(captor.getValue().roleId()).isEqualTo(roleId);
        assertThat(captor.getValue().active()).isFalse();
        assertThat(captor.getValue().attributes()).isNull();
        // Omitted = unchanged; only the allow-list (sent as []) and the daily limit moved.
        assertThat(entity.getDescription()).isEqualTo("desc");
        assertThat(entity.getOwnerUserId()).isEqualTo(ACTOR);
        assertThat(entity.getMcpToolAllowList()).isEmpty();
        assertThat(entity.getRateLimitPerMinute()).isEqualTo(5);
        assertThat(entity.getRateLimitPerDay()).isEqualTo(500);
    }

    @Test
    void updateSkipsTheUserServiceWhenNoDeclaredFieldIsSentAndKeepsAnOmittedAllowList() {
        stubLoad();
        stubUser();
        when(repository.save(entity)).thenReturn(entity);
        when(apiKeyService.list(userId)).thenReturn(List.of());

        service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(null, null, null, null,
                "new desc", null, null, 1, 2, null));

        verify(userAdminService, never()).updateUser(any(), any(), any(), any());
        assertThat(entity.getDescription()).isEqualTo("new desc");
        // The one write that could widen: an omitted allow-list must never re-open every tool.
        assertThat(entity.getMcpToolAllowList()).containsExactly("validate_sql");
        assertThat(entity.getRateLimitPerMinute()).isEqualTo(1);
        assertThat(entity.getRateLimitPerDay()).isEqualTo(2);
    }

    @Test
    void updateClearsOnlyTheFieldsNamedInClear() {
        stubLoad();
        stubUser();
        when(repository.save(entity)).thenReturn(entity);
        when(apiKeyService.list(userId)).thenReturn(List.of());
        entity.setOwnerUserId(ACTOR);
        entity.setRateLimitPerMinute(5);
        entity.setRateLimitPerDay(50);

        service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(null, null, null, null,
                null, null, null, null, null, Set.of(ServiceAccountClearableField.MCP_TOOL_ALLOW_LIST,
                ServiceAccountClearableField.OWNER_USER_ID, ServiceAccountClearableField.RATE_LIMIT_PER_DAY)));

        assertThat(entity.getMcpToolAllowList()).isNull();
        assertThat(entity.getOwnerUserId()).isNull();
        assertThat(entity.getRateLimitPerDay()).isNull();
        assertThat(entity.getDescription()).isEqualTo("desc");
        assertThat(entity.getRateLimitPerMinute()).isEqualTo(5);

        service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(null, null, null, null,
                null, null, null, null, null, Set.of(ServiceAccountClearableField.DESCRIPTION,
                ServiceAccountClearableField.RATE_LIMIT_PER_MINUTE)));
        assertThat(entity.getDescription()).isNull();
        assertThat(entity.getRateLimitPerMinute()).isNull();
    }

    @Test
    void updateRefusesAChangedDisplayNameOnABootstrapAccount() {
        entity.setManagedBy(ServiceAccountSource.BOOTSTRAP);
        stubLoad();
        stubUser();

        assertThatThrownBy(() -> service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(
                "Other", null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ServiceAccountBootstrapManagedException.class)
                .satisfies(ex -> assertThat(((ServiceAccountBootstrapManagedException) ex).field())
                        .isEqualTo("display_name"));
        verify(userAdminService, never()).updateUser(any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void updateRefusesAChangedRoleOrRoleIdOnABootstrapAccount() {
        entity.setManagedBy(ServiceAccountSource.BOOTSTRAP);
        stubLoad();
        stubUser();

        assertThatThrownBy(() -> service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(
                null, UserRoleType.ADMIN, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ServiceAccountBootstrapManagedException.class)
                .satisfies(ex -> assertThat(((ServiceAccountBootstrapManagedException) ex).field())
                        .isEqualTo("role"));
        assertThatThrownBy(() -> service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(
                null, null, UUID.randomUUID(), null, null, null, null, null, null, null)))
                .isInstanceOf(ServiceAccountBootstrapManagedException.class)
                .satisfies(ex -> assertThat(((ServiceAccountBootstrapManagedException) ex).field())
                        .isEqualTo("role_id"));
    }

    @Test
    void updateAcceptsUnchangedDeclaredValuesAndUiOwnedEditsOnABootstrapAccount() {
        entity.setManagedBy(ServiceAccountSource.BOOTSTRAP);
        stubLoad();
        var current = user(userId);
        when(userAdminService.findByIds(ORG, List.of(userId))).thenReturn(Map.of(userId, current));
        when(repository.save(entity)).thenReturn(entity);
        when(apiKeyService.list(userId)).thenReturn(List.of());

        service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(current.displayName(),
                current.role(), current.roleId(), true, "edited", null, List.of("validate_sql"), 10, 20, null));

        // An equal declared value is a no-op for the guard but still flows through updateUser
        // (with active), exactly like a UI account.
        verify(userAdminService).updateUser(eq(userId), eq(ORG), eq(ACTOR), any());
        assertThat(entity.getDescription()).isEqualTo("edited");
        assertThat(entity.getRateLimitPerMinute()).isEqualTo(10);
    }

    @Test
    void updateValidatesOwnerAndToolsBeforeWriting() {
        stubLoad();
        assertThatThrownBy(() -> service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(
                null, null, null, null, null, null, List.of("nope"), null, null, null)))
                .isInstanceOf(ServiceAccountUnknownMcpToolException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateOfAMissingAccountIsNotFound() {
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(ORG, userId, ACTOR, new UpdateServiceAccountCommand(
                null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ServiceAccountNotFoundException.class);
    }

    // ---- deactivate -------------------------------------------------------------------------

    @Test
    void deactivateDelegatesToTheUserServiceAfterTheOrgScopedGuard() {
        stubLoad();
        service.deactivate(ORG, userId, ACTOR);
        verify(userAdminService).deactivateUser(userId, ORG, ACTOR);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void deactivateOfAMissingAccountIsNotFound() {
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate(ORG, userId, ACTOR))
                .isInstanceOf(ServiceAccountNotFoundException.class);
        verify(userAdminService, never()).deactivateUser(any(), any(), any());
    }

    // ---- keys -------------------------------------------------------------------------------

    @Test
    void issueKeyIssuesOnBehalfOfTheAccountAndReturnsThePlaintextOnce() {
        stubLoad();
        var expires = NOW.plusSeconds(3600);
        var view = key(userId, "ci", expires, null, false, null);
        when(apiKeyService.issue(userId, ORG, "ci", expires)).thenReturn(new IssuedApiKey(view, "af_raw"));

        var issued = service.issueKey(ORG, userId, new IssueServiceAccountKeyCommand("ci", expires));

        assertThat(issued.rawKey()).isEqualTo("af_raw");
        assertThat(issued.apiKey().id()).isEqualTo(view.id());
        assertThat(issued.apiKey().expiresAt()).isEqualTo(expires);
        assertThat(issued.apiKey().bootstrapDeclared()).isFalse();
    }

    @Test
    void issueKeyTranslatesADuplicateNameIntoTheModulesConflict() {
        stubLoad();
        when(apiKeyService.issue(userId, ORG, "ci", null)).thenThrow(new ApiKeyDuplicateNameException("ci"));
        assertThatThrownBy(() -> service.issueKey(ORG, userId, new IssueServiceAccountKeyCommand("ci", null)))
                .isInstanceOf(ServiceAccountKeyNameConflictException.class)
                .satisfies(ex -> assertThat(((ServiceAccountKeyNameConflictException) ex).name()).isEqualTo("ci"));
    }

    @Test
    void rotateKeyIssuesTheReplacementAndExpiresTheOldKeyAfterTheDefaultGrace() {
        stubLoad();
        var old = key(userId, "ci", null, null, false, NOW.minusSeconds(60));
        when(apiKeyService.list(userId)).thenReturn(List.of(old));
        var replacement = key(userId, "ci-2", null, null, false, null);
        when(apiKeyService.issue(userId, ORG, "ci-2", null)).thenReturn(new IssuedApiKey(replacement, "af_new"));

        var rotated = service.rotateKey(ORG, userId, old.id(), new RotateServiceAccountKeyCommand("ci-2", null, null));

        verify(apiKeyService).expireAt(userId, old.id(), NOW.plus(Duration.ofHours(24)));
        verify(apiKeyService, never()).revoke(any(), any());
        assertThat(rotated.rawKey()).isEqualTo("af_new");
        assertThat(rotated.apiKey().name()).isEqualTo("ci-2");
        assertThat(rotated.supersededKey().id()).isEqualTo(old.id());
        assertThat(rotated.supersededKey().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(rotated.supersededKey().revokedAt()).isNull();
        assertThat(rotated.supersededKey().lastUsedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void rotateKeyHonoursARequestGraceAndKeepsAnEarlierExistingExpiry() {
        stubLoad();
        var soon = NOW.plusSeconds(30);
        var old = key(userId, "ci", soon, null, false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(old));
        when(apiKeyService.issue(userId, ORG, "ci-2", soon))
                .thenReturn(new IssuedApiKey(key(userId, "ci-2", soon, null, false, null), "af_new"));

        var rotated = service.rotateKey(ORG, userId, old.id(),
                new RotateServiceAccountKeyCommand("ci-2", soon, Duration.ofSeconds(1)));

        verify(apiKeyService).expireAt(userId, old.id(), NOW.plusSeconds(1));
        assertThat(rotated.supersededKey().expiresAt()).isEqualTo(NOW.plusSeconds(1));

        var later = key(userId, "ci-3", null, null, false, null);
        var oldSoon = key(userId, "ci-4", soon, null, false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(oldSoon));
        when(apiKeyService.issue(userId, ORG, "ci-5", null)).thenReturn(new IssuedApiKey(later, "af_x"));

        service.rotateKey(ORG, userId, oldSoon.id(), new RotateServiceAccountKeyCommand("ci-5", null, null));

        verify(apiKeyService).expireAt(userId, oldSoon.id(), soon);
    }

    @Test
    void rotateKeyRejectsANonPositiveGrace() {
        stubLoad();
        var old = key(userId, "ci", null, null, false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(old));
        assertThatThrownBy(() -> service.rotateKey(ORG, userId, old.id(),
                new RotateServiceAccountKeyCommand("ci-2", null, Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(apiKeyService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void rotateKeyRefusesTheBootstrapDeclaredKey() {
        stubLoad();
        var declared = key(userId, "terraform", null, null, true, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(declared));
        assertThatThrownBy(() -> service.rotateKey(ORG, userId, declared.id(),
                new RotateServiceAccountKeyCommand("x", null, null)))
                .isInstanceOf(ServiceAccountKeyBootstrapDeclaredException.class)
                .satisfies(ex -> assertThat(((ServiceAccountKeyBootstrapDeclaredException) ex).apiKeyId())
                        .isEqualTo(declared.id()));
        verify(apiKeyService, never()).issue(any(), any(), any(), any());
        verify(apiKeyService, never()).expireAt(any(), any(), any());
    }

    @Test
    void rotateKeyRefusesARevokedKey() {
        stubLoad();
        var revoked = key(userId, "old", null, NOW.minusSeconds(5), false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(revoked));
        assertThatThrownBy(() -> service.rotateKey(ORG, userId, revoked.id(),
                new RotateServiceAccountKeyCommand("x", null, null)))
                .isInstanceOf(ServiceAccountKeyRevokedException.class)
                .satisfies(ex -> assertThat(((ServiceAccountKeyRevokedException) ex).apiKeyId())
                        .isEqualTo(revoked.id()));
    }

    @Test
    void rotateKeyTreatsAForeignOrUnknownKeyAsNotFound() {
        stubLoad();
        when(apiKeyService.list(userId)).thenReturn(List.of(key(userId, "other", null, null, false, null)));
        var unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.rotateKey(ORG, userId, unknown,
                new RotateServiceAccountKeyCommand("x", null, null)))
                .isInstanceOf(ServiceAccountKeyNotFoundException.class)
                .satisfies(ex -> assertThat(((ServiceAccountKeyNotFoundException) ex).apiKeyId()).isEqualTo(unknown));
    }

    @Test
    void rotateKeyPropagatesAReplacementNameConflictWithoutTouchingTheOldKey() {
        stubLoad();
        var old = key(userId, "ci", null, null, false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(old));
        when(apiKeyService.issue(userId, ORG, "ci", null)).thenThrow(new ApiKeyDuplicateNameException("ci"));
        assertThatThrownBy(() -> service.rotateKey(ORG, userId, old.id(),
                new RotateServiceAccountKeyCommand("ci", null, null)))
                .isInstanceOf(ServiceAccountKeyNameConflictException.class);
        verify(apiKeyService, never()).expireAt(any(), any(), any());
    }

    @Test
    void revokeKeyDelegatesForAnOrdinaryKey() {
        stubLoad();
        var key = key(userId, "ci", null, null, false, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(key));
        service.revokeKey(ORG, userId, key.id());
        verify(apiKeyService).revoke(userId, key.id());
    }

    @Test
    void revokeKeyRefusesTheBootstrapDeclaredKeyBeforeReachingTheSecurityService() {
        stubLoad();
        var declared = key(userId, "terraform", null, null, true, null);
        when(apiKeyService.list(userId)).thenReturn(List.of(declared));
        assertThatThrownBy(() -> service.revokeKey(ORG, userId, declared.id()))
                .isInstanceOf(ServiceAccountKeyBootstrapDeclaredException.class);
        verify(apiKeyService, never()).revoke(any(), any());
    }

    @Test
    void revokeKeyOfAnUnknownKeyIsNotFound() {
        stubLoad();
        when(apiKeyService.list(userId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.revokeKey(ORG, userId, UUID.randomUUID()))
                .isInstanceOf(ServiceAccountKeyNotFoundException.class);
    }

    @Test
    void keyOperationsOnAMissingAccountAreNotFound() {
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issueKey(ORG, userId, new IssueServiceAccountKeyCommand("ci", null)))
                .isInstanceOf(ServiceAccountNotFoundException.class);
        assertThatThrownBy(() -> service.revokeKey(ORG, userId, UUID.randomUUID()))
                .isInstanceOf(ServiceAccountNotFoundException.class);
        verifyNoInteractions(apiKeyService);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void stubLoad() {
        when(repository.findByUserIdAndOrganizationId(userId, ORG)).thenReturn(Optional.of(entity));
    }

    private void stubUser() {
        when(userAdminService.findByIds(ORG, List.of(userId))).thenReturn(Map.of(userId, user(userId)));
    }

    private static CreateServiceAccountCommand command(UUID owner, List<String> tools) {
        return new CreateServiceAccountCommand("bot@example.com", "Bot", null, null, null, owner, tools, null, null);
    }

    private static ServiceAccountEntity entity(UUID userId, ServiceAccountSource source) {
        var e = new ServiceAccountEntity();
        e.setUserId(userId);
        e.setOrganizationId(ORG);
        e.setManagedBy(source);
        e.setDescription("desc");
        e.setMcpToolAllowList(new String[] {"validate_sql"});
        return e;
    }

    private static UserView user(UUID id) {
        return user(id, true, PrincipalType.SERVICE_ACCOUNT);
    }

    private static UserView user(UUID id, boolean active, PrincipalType principalType) {
        return new UserView(id, id + "@example.com", "Bot", UserRoleType.ANALYST, UUID.randomUUID(), "ANALYST",
                ORG, active, AuthProviderType.LOCAL, "hash", null, "en", false, false, NOW, null, NOW,
                principalType);
    }

    private static ApiKeyView key(UUID owner, String name, Instant expiresAt, Instant revokedAt,
                                  boolean declared, Instant lastUsedAt) {
        return new ApiKeyView(UUID.randomUUID(), owner, ORG, name, "af_" + name, NOW.minusSeconds(3600),
                lastUsedAt, expiresAt, revokedAt, declared);
    }
}
