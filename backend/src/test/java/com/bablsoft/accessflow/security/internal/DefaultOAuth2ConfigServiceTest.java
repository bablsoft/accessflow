package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigInvalidException;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ConfigDeletedEvent;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ConfigUpdatedEvent;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOAuth2ConfigServiceTest {

    @Mock OAuth2ConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock ApplicationEventPublisher publisher;
    @Mock MessageSource messageSource;

    private DefaultOAuth2ConfigService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultOAuth2ConfigService(repository, encryptionService, publisher, messageSource);
    }

    @Test
    void listReturnsOneEntryPerProviderEvenWhenNoneSaved() {
        when(repository.findAllByOrganizationId(orgId)).thenReturn(List.of());

        var entries = service.list(orgId);

        assertThat(entries).hasSize(OAuth2ProviderType.values().length);
        assertThat(entries.stream().map(v -> v.provider()).toList())
                .containsExactlyInAnyOrder(OAuth2ProviderType.values());
        assertThat(entries).allSatisfy(v -> {
            assertThat(v.active()).isFalse();
            assertThat(v.clientSecretConfigured()).isFalse();
            assertThat(v.allowedOrganizations()).isEmpty();
            assertThat(v.allowedEmailDomains()).isEmpty();
        });
    }

    @Test
    void getOrDefaultReturnsTransientDefaults() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());

        var view = service.getOrDefault(orgId, OAuth2ProviderType.GOOGLE);

        assertThat(view.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
        assertThat(view.active()).isFalse();
        assertThat(view.clientId()).isNull();
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.ANALYST);
        assertThat(view.allowedOrganizations()).isEmpty();
        assertThat(view.allowedEmailDomains()).isEmpty();
    }

    @Test
    void updateUpsertsAndEncryptsSecretOnChange() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("secret123")).thenReturn("ENC(secret123)");

        var view = service.update(orgId, OAuth2ProviderType.GOOGLE, command()
                .clientId("client-abc")
                .clientSecret("secret123")
                .scopesOverride("openid email")
                .defaultRole(UserRoleType.REVIEWER)
                .active(true)
                .build());

        assertThat(view.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
        assertThat(view.clientId()).isEqualTo("client-abc");
        assertThat(view.clientSecretConfigured()).isTrue();
        assertThat(view.active()).isTrue();
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.REVIEWER);
        verify(encryptionService).encrypt("secret123");
        verify(publisher).publishEvent(any(OAuth2ConfigUpdatedEvent.class));
    }

    @Test
    void updateLeavesSecretWhenMaskedPlaceholderProvided() {
        var entity = seeded(OAuth2ProviderType.GITHUB);
        entity.setClientId("existing");
        entity.setClientSecretEncrypted("ENC(prior)");
        entity.setActive(true);
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITHUB))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, OAuth2ProviderType.GITHUB,
                command()
                        .clientSecret(UpdateOAuth2ConfigCommand.MASKED_SECRET)
                        .active(true)
                        .build());

        var captor = ArgumentCaptor.forClass(OAuth2ConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getClientSecretEncrypted()).isEqualTo("ENC(prior)");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateClearsSecretAndDeactivatesWhenBlankProvided() {
        var entity = seeded(OAuth2ProviderType.GITLAB);
        entity.setClientId("existing");
        entity.setClientSecretEncrypted("ENC(prior)");
        entity.setActive(true);
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITLAB))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, OAuth2ProviderType.GITLAB,
                command().clientSecret("").build());

        assertThat(view.clientSecretConfigured()).isFalse();
        assertThat(view.active()).isFalse();
    }

    @Test
    void updateRejectsActivationWithoutClientId() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("client_id required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.GOOGLE,
                command().active(true).build()))
                .isInstanceOf(OAuth2ConfigInvalidException.class);
    }

    @Test
    void updateRejectsMicrosoftActivationWithoutTenant() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.MICROSOFT))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("s")).thenReturn("E");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("tenant required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.MICROSOFT,
                command().clientId("c").clientSecret("s").active(true).build()))
                .isInstanceOf(OAuth2ConfigInvalidException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void updateRejectsGithubActivationWithAllowedOrgsButWithoutReadOrgScope() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITHUB))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("s")).thenReturn("E");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("read:org required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.GITHUB,
                command().clientId("c").clientSecret("s")
                        .scopesOverride("read:user user:email")
                        .allowedOrganizations(List.of("bablsoft"))
                        .active(true).build()))
                .isInstanceOf(OAuth2ConfigInvalidException.class)
                .hasMessageContaining("read:org");
    }

    @Test
    void updateAllowsGithubActivationWhenAllowedOrgsSetAndReadOrgPresent() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITHUB))
                .thenReturn(Optional.empty());
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("s")).thenReturn("E");

        var view = service.update(orgId, OAuth2ProviderType.GITHUB, command()
                .clientId("c").clientSecret("s")
                .scopesOverride("read:user user:email read:org")
                .allowedOrganizations(List.of("bablsoft", "acme"))
                .active(true).build());

        assertThat(view.allowedOrganizations()).containsExactly("bablsoft", "acme");
        assertThat(view.active()).isTrue();
    }

    @Test
    void updateNormalizesEmailDomainsToLowercaseAndDeduplicates() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("s")).thenReturn("E");

        var view = service.update(orgId, OAuth2ProviderType.GOOGLE, command()
                .clientId("c").clientSecret("s")
                .allowedEmailDomains(List.of("Example.com", "  ACME.com  ", "example.com"))
                .active(true).build());

        assertThat(view.allowedEmailDomains()).containsExactly("example.com", "acme.com");
    }

    @Test
    void updateClearsAllowlistsWhenEmptyListProvided() {
        var entity = seeded(OAuth2ProviderType.GOOGLE);
        entity.setClientId("c");
        entity.setClientSecretEncrypted("E");
        entity.setActive(true);
        entity.setAllowedOrganizations(new String[]{"x"});
        entity.setAllowedEmailDomains(new String[]{"y.com"});
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, OAuth2ProviderType.GOOGLE, command()
                .clientSecret(UpdateOAuth2ConfigCommand.MASKED_SECRET)
                .allowedOrganizations(List.of())
                .allowedEmailDomains(List.of())
                .active(true).build());

        assertThat(view.allowedOrganizations()).isEmpty();
        assertThat(view.allowedEmailDomains()).isEmpty();
    }

    @Test
    void updateOidcRejectsActivationWhenDisplayNameMissing() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.OIDC))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("s")).thenReturn("E");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("display_name required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.OIDC, command()
                .clientId("c").clientSecret("s")
                .authorizationUri("http://idp/authorize")
                .tokenUri("http://idp/token")
                .userInfoUri("http://idp/userinfo")
                .jwkSetUri("http://idp/jwks")
                .issuerUri("http://idp")
                .active(true).build()))
                .isInstanceOf(OAuth2ConfigInvalidException.class)
                .hasMessageContaining("display_name");
    }

    @Test
    void updateOidcRejectsActivationWhenUriMissing() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.OIDC))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("s")).thenReturn("E");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("token uri required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.OIDC, command()
                .clientId("c").clientSecret("s").displayName("Mock IdP")
                .authorizationUri("http://idp/authorize")
                .userInfoUri("http://idp/userinfo")
                .jwkSetUri("http://idp/jwks")
                .issuerUri("http://idp")
                .active(true).build()))
                .isInstanceOf(OAuth2ConfigInvalidException.class)
                .hasMessageContaining("token uri");
    }

    @Test
    void updateOidcRejectsMalformedUri() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.OIDC))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("s")).thenReturn("E");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("invalid URL");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.OIDC, command()
                .clientId("c").clientSecret("s").displayName("Mock IdP")
                .authorizationUri("not-a-url")
                .tokenUri("http://idp/token")
                .userInfoUri("http://idp/userinfo")
                .jwkSetUri("http://idp/jwks")
                .issuerUri("http://idp")
                .active(true).build()))
                .isInstanceOf(OAuth2ConfigInvalidException.class);
    }

    @Test
    void updateOidcHappyPathStoresAllFields() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.OIDC))
                .thenReturn(Optional.empty());
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("s")).thenReturn("E");

        var view = service.update(orgId, OAuth2ProviderType.OIDC, command()
                .clientId("c").clientSecret("s")
                .displayName("Mock IdP")
                .authorizationUri("http://idp/authorize")
                .tokenUri("http://idp/token")
                .userInfoUri("http://idp/userinfo")
                .jwkSetUri("http://idp/jwks")
                .issuerUri("http://idp")
                .userNameAttribute("sub")
                .emailAttribute("email")
                .emailVerifiedAttribute("email_verified")
                .displayNameAttribute("name")
                .groupsAttribute("groups")
                .active(true).build());

        assertThat(view.provider()).isEqualTo(OAuth2ProviderType.OIDC);
        assertThat(view.displayName()).isEqualTo("Mock IdP");
        assertThat(view.authorizationUri()).isEqualTo("http://idp/authorize");
        assertThat(view.tokenUri()).isEqualTo("http://idp/token");
        assertThat(view.userInfoUri()).isEqualTo("http://idp/userinfo");
        assertThat(view.jwkSetUri()).isEqualTo("http://idp/jwks");
        assertThat(view.issuerUri()).isEqualTo("http://idp");
        assertThat(view.userNameAttribute()).isEqualTo("sub");
        assertThat(view.emailAttribute()).isEqualTo("email");
        assertThat(view.emailVerifiedAttribute()).isEqualTo("email_verified");
        assertThat(view.displayNameAttribute()).isEqualTo("name");
        assertThat(view.groupsAttribute()).isEqualTo("groups");
        assertThat(view.active()).isTrue();
    }

    @Test
    void listActivePublishesOnlyEnabledRows() {
        var enabled = seeded(OAuth2ProviderType.GOOGLE);
        enabled.setActive(true);
        when(repository.findAllByOrganizationIdAndActiveTrue(orgId))
                .thenReturn(List.of(enabled));

        var active = service.listActive(orgId);

        assertThat(active).singleElement().satisfies(s -> {
            assertThat(s.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
            assertThat(s.displayName()).isEqualTo("Google");
        });
    }

    @Test
    void listActiveOidcUsesEntityDisplayName() {
        var enabled = seeded(OAuth2ProviderType.OIDC);
        enabled.setActive(true);
        enabled.setDisplayName("Mock IdP");
        when(repository.findAllByOrganizationIdAndActiveTrue(orgId))
                .thenReturn(List.of(enabled));

        var active = service.listActive(orgId);

        assertThat(active).singleElement().satisfies(s -> {
            assertThat(s.provider()).isEqualTo(OAuth2ProviderType.OIDC);
            assertThat(s.displayName()).isEqualTo("Mock IdP");
        });
    }

    @Test
    void deletePublishesEvent() {
        service.delete(orgId, OAuth2ProviderType.GITHUB);

        verify(repository).deleteByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITHUB);
        verify(publisher).publishEvent(any(OAuth2ConfigDeletedEvent.class));
    }

    private OAuth2ConfigEntity seeded(OAuth2ProviderType provider) {
        var entity = new OAuth2ConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setProvider(provider);
        entity.setDefaultRole(UserRoleType.ANALYST);
        return entity;
    }

    private static CommandBuilder command() {
        return new CommandBuilder();
    }

    /** Builder for {@link UpdateOAuth2ConfigCommand} so tests stay readable as the field set grows. */
    private static final class CommandBuilder {
        private String clientId;
        private String clientSecret;
        private String scopesOverride;
        private String tenantId;
        private String displayName;
        private String authorizationUri;
        private String tokenUri;
        private String userInfoUri;
        private String jwkSetUri;
        private String issuerUri;
        private String userNameAttribute;
        private String emailAttribute;
        private String emailVerifiedAttribute;
        private String displayNameAttribute;
        private String groupsAttribute;
        private List<String> allowedOrganizations;
        private List<String> allowedEmailDomains;
        private UserRoleType defaultRole = UserRoleType.ANALYST;
        private Boolean active;

        CommandBuilder clientId(String v) { this.clientId = v; return this; }
        CommandBuilder clientSecret(String v) { this.clientSecret = v; return this; }
        CommandBuilder scopesOverride(String v) { this.scopesOverride = v; return this; }
        CommandBuilder tenantId(String v) { this.tenantId = v; return this; }
        CommandBuilder displayName(String v) { this.displayName = v; return this; }
        CommandBuilder authorizationUri(String v) { this.authorizationUri = v; return this; }
        CommandBuilder tokenUri(String v) { this.tokenUri = v; return this; }
        CommandBuilder userInfoUri(String v) { this.userInfoUri = v; return this; }
        CommandBuilder jwkSetUri(String v) { this.jwkSetUri = v; return this; }
        CommandBuilder issuerUri(String v) { this.issuerUri = v; return this; }
        CommandBuilder userNameAttribute(String v) { this.userNameAttribute = v; return this; }
        CommandBuilder emailAttribute(String v) { this.emailAttribute = v; return this; }
        CommandBuilder emailVerifiedAttribute(String v) { this.emailVerifiedAttribute = v; return this; }
        CommandBuilder displayNameAttribute(String v) { this.displayNameAttribute = v; return this; }
        CommandBuilder groupsAttribute(String v) { this.groupsAttribute = v; return this; }
        CommandBuilder allowedOrganizations(List<String> v) { this.allowedOrganizations = v; return this; }
        CommandBuilder allowedEmailDomains(List<String> v) { this.allowedEmailDomains = v; return this; }
        CommandBuilder defaultRole(UserRoleType v) { this.defaultRole = v; return this; }
        CommandBuilder active(Boolean v) { this.active = v; return this; }

        UpdateOAuth2ConfigCommand build() {
            return new UpdateOAuth2ConfigCommand(
                    clientId, clientSecret, scopesOverride, tenantId,
                    displayName, authorizationUri, tokenUri, userInfoUri, jwkSetUri, issuerUri,
                    userNameAttribute, emailAttribute, emailVerifiedAttribute,
                    displayNameAttribute, groupsAttribute,
                    allowedOrganizations, allowedEmailDomains, defaultRole, active);
        }
    }
}
