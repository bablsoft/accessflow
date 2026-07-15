package com.bablsoft.accessflow.core.internal.secrets;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.core.credential.TokenCredential;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.bablsoft.accessflow.core.internal.config.SecretsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.KubernetesAuthentication;
import org.springframework.vault.authentication.KubernetesAuthenticationOptions;
import org.springframework.vault.authentication.KubernetesServiceAccountTokenFile;
import org.springframework.vault.authentication.LifecycleAwareSessionManager;
import org.springframework.vault.authentication.SessionManager;
import org.springframework.vault.authentication.SimpleSessionManager;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.ClientHttpRequestFactoryFactory;
import org.springframework.vault.client.RestTemplateBuilder;
import org.springframework.vault.client.VaultClients;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.ClientOptions;
import org.springframework.vault.support.SslConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Instantiates one {@link SecretStore} bean per enabled provider (AF-448). Providers are
 * strictly opt-in via {@code accessflow.secrets.<provider>.enabled}; when disabled, no client
 * is built and no bean exists, so {@link DefaultSecretResolutionService} sees exactly the
 * enabled providers. Store clients are validated fail-fast at startup (missing required
 * settings abort boot with an operator-facing message). Client/session cleanup is owned here
 * ({@link DisposableBean}) rather than by the stores.
 */
@Configuration
class SecretsConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SecretsConfiguration.class);

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @Bean
    @ConditionalOnProperty(prefix = "accessflow.secrets.vault", name = "enabled", havingValue = "true")
    SecretStore vaultSecretStore(SecretsProperties properties) {
        var vault = properties.vault();
        Assert.hasText(vault.uri(), "accessflow.secrets.vault.uri (ACCESSFLOW_SECRETS_VAULT_URI)"
                + " is required when the Vault secret store is enabled");
        var endpoint = VaultEndpoint.from(URI.create(vault.uri()));
        ClientHttpRequestFactory requestFactory =
                ClientHttpRequestFactoryFactory.create(new ClientOptions(), SslConfiguration.unconfigured());
        var restTemplateBuilder = RestTemplateBuilder.builder()
                .endpoint(endpoint)
                .requestFactory(requestFactory);
        var authTemplate = VaultClients.createRestTemplate(endpoint, requestFactory);
        if (vault.namespace() != null && !vault.namespace().isBlank()) {
            var namespaceInterceptor = VaultClients.createNamespaceInterceptor(vault.namespace());
            restTemplateBuilder.customizers(rt -> rt.getInterceptors().add(namespaceInterceptor));
            authTemplate.getInterceptors().add(namespaceInterceptor);
        }
        var vaultTemplate = new VaultTemplate(restTemplateBuilder, sessionManager(vault, authTemplate));
        return new VaultSecretStore(vaultTemplate, vault.kvVersion());
    }

    private SessionManager sessionManager(SecretsProperties.Vault vault,
                                          org.springframework.web.client.RestTemplate authTemplate) {
        return switch (vault.authMethod().toUpperCase(Locale.ROOT)) {
            case "TOKEN" -> {
                Assert.hasText(vault.token(), "accessflow.secrets.vault.token"
                        + " (ACCESSFLOW_SECRETS_VAULT_TOKEN) is required for the TOKEN auth method");
                yield new SimpleSessionManager(new TokenAuthentication(vault.token()));
            }
            case "APPROLE" -> {
                Assert.hasText(vault.appRoleId(), "accessflow.secrets.vault.app-role-id"
                        + " (ACCESSFLOW_SECRETS_VAULT_APP_ROLE_ID) is required for the APPROLE auth method");
                Assert.hasText(vault.appRoleSecretId(), "accessflow.secrets.vault.app-role-secret-id"
                        + " (ACCESSFLOW_SECRETS_VAULT_APP_ROLE_SECRET_ID) is required for the APPROLE auth method");
                var options = AppRoleAuthenticationOptions.builder()
                        .roleId(AppRoleAuthenticationOptions.RoleId.provided(vault.appRoleId()))
                        .secretId(AppRoleAuthenticationOptions.SecretId.provided(vault.appRoleSecretId()))
                        .build();
                yield lifecycleSessionManager(new AppRoleAuthentication(options, authTemplate), authTemplate);
            }
            case "KUBERNETES" -> {
                Assert.hasText(vault.kubernetesRole(), "accessflow.secrets.vault.kubernetes-role"
                        + " (ACCESSFLOW_SECRETS_VAULT_KUBERNETES_ROLE) is required for the KUBERNETES auth method");
                var options = KubernetesAuthenticationOptions.builder()
                        .role(vault.kubernetesRole())
                        .path(vault.kubernetesAuthPath())
                        .jwtSupplier(new KubernetesServiceAccountTokenFile())
                        .build();
                yield lifecycleSessionManager(new KubernetesAuthentication(options, authTemplate), authTemplate);
            }
            default -> throw new IllegalStateException(
                    "Unsupported accessflow.secrets.vault.auth-method '" + vault.authMethod()
                            + "' — expected TOKEN, APPROLE or KUBERNETES");
        };
    }

    /**
     * AppRole / Kubernetes login tokens are renewable: {@link LifecycleAwareSessionManager}
     * schedules renewal and re-login on a small dedicated scheduler. The scheduler is
     * deliberately <em>not</em> a Spring bean — a context-level {@code TaskScheduler} bean
     * would make Boot's scheduling auto-configuration back off and hijack every
     * {@code @Scheduled} job onto this single-thread pool.
     */
    private SessionManager lifecycleSessionManager(
            ClientAuthentication authentication,
            org.springframework.web.client.RestTemplate authTemplate) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("vault-session-");
        scheduler.initialize();
        var sessionManager = new LifecycleAwareSessionManager(authentication, scheduler, authTemplate);
        closeables.add(sessionManager::destroy);
        closeables.add(scheduler::destroy);
        return sessionManager;
    }

    @Bean
    @ConditionalOnProperty(prefix = "accessflow.secrets.aws", name = "enabled", havingValue = "true")
    SecretStore awsSecretsManagerSecretStore(SecretsProperties properties, ObjectMapper objectMapper) {
        var aws = properties.aws();
        // No Netty: same url-connection HTTP client choice as the DynamoDB engine plugin.
        var builder = SecretsManagerClient.builder()
                .httpClient(UrlConnectionHttpClient.builder().build());
        if (aws.region() != null && !aws.region().isBlank()) {
            builder.region(Region.of(aws.region()));
        }
        if (aws.endpointOverride() != null && !aws.endpointOverride().isBlank()) {
            builder.endpointOverride(URI.create(aws.endpointOverride()));
        }
        if (aws.accessKeyId() != null && !aws.accessKeyId().isBlank()) {
            Assert.hasText(aws.secretAccessKey(), "accessflow.secrets.aws.secret-access-key"
                    + " (ACCESSFLOW_SECRETS_AWS_SECRET_ACCESS_KEY) is required when an access key id is set");
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())));
        }
        var client = builder.build();
        closeables.add(client);
        return new AwsSecretsManagerSecretStore(client, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "accessflow.secrets.azure", name = "enabled", havingValue = "true")
    SecretStore azureKeyVaultSecretStore(SecretsProperties properties) {
        var azure = properties.azure();
        Assert.hasText(azure.vaultUrl(), "accessflow.secrets.azure.vault-url"
                + " (ACCESSFLOW_SECRETS_AZURE_VAULT_URL) is required when the Azure secret store is enabled");
        boolean explicitClient = azure.clientId() != null && !azure.clientId().isBlank();
        TokenCredential credential;
        if (explicitClient) {
            Assert.hasText(azure.tenantId(), "accessflow.secrets.azure.tenant-id"
                    + " (ACCESSFLOW_SECRETS_AZURE_TENANT_ID) is required when a client id is set");
            Assert.hasText(azure.clientSecret(), "accessflow.secrets.azure.client-secret"
                    + " (ACCESSFLOW_SECRETS_AZURE_CLIENT_SECRET) is required when a client id is set");
            credential = new ClientSecretCredentialBuilder()
                    .tenantId(azure.tenantId())
                    .clientId(azure.clientId())
                    .clientSecret(azure.clientSecret())
                    .build();
        } else {
            credential = new DefaultAzureCredentialBuilder().build();
        }
        SecretClient client = new SecretClientBuilder()
                .vaultUrl(azure.vaultUrl())
                .credential(credential)
                .buildClient();
        return new AzureKeyVaultSecretStore(client);
    }

    @Override
    public void destroy() {
        for (var closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception ex) { // AutoCloseable.close() declares Exception
                log.warn("Secret-store client cleanup failed on shutdown", ex);
            }
        }
    }
}
