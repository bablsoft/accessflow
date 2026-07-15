package com.bablsoft.accessflow.core.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External secret-store configuration (AF-448). Env-driven per deployment; each provider is
 * opt-in via its {@code enabled} flag and its beans are only created when enabled — see
 * {@code core.internal.secrets.SecretsConfiguration}.
 */
@ConfigurationProperties(prefix = "accessflow.secrets")
public record SecretsProperties(Vault vault, Aws aws, Azure azure) {

    public SecretsProperties {
        vault = vault == null ? new Vault(false, null, null, null, null, null, null, null, 2, null) : vault;
        aws = aws == null ? new Aws(false, null, null, null, null) : aws;
        azure = azure == null ? new Azure(false, null, null, null, null) : azure;
    }

    /**
     * HashiCorp Vault. {@code authMethod} is {@code TOKEN} (default), {@code APPROLE}
     * ({@code appRoleId}/{@code appRoleSecretId}) or {@code KUBERNETES}
     * ({@code kubernetesRole}, service-account JWT). {@code kvVersion} selects the KV engine
     * version used to build read paths (default 2). {@code namespace} is for Vault Enterprise.
     */
    public record Vault(boolean enabled, String uri, String authMethod, String token,
                        String appRoleId, String appRoleSecretId, String kubernetesRole,
                        String kubernetesAuthPath, int kvVersion, String namespace) {
        public Vault {
            authMethod = authMethod == null || authMethod.isBlank() ? "TOKEN" : authMethod;
            kubernetesAuthPath = kubernetesAuthPath == null || kubernetesAuthPath.isBlank()
                    ? "kubernetes" : kubernetesAuthPath;
            kvVersion = kvVersion == 0 ? 2 : kvVersion;
        }
    }

    /**
     * AWS Secrets Manager. When {@code accessKeyId}/{@code secretAccessKey} are unset the SDK's
     * default credentials provider chain applies (env vars, IRSA, instance profile).
     * {@code endpointOverride} targets LocalStack / VPC endpoints.
     */
    public record Aws(boolean enabled, String region, String endpointOverride,
                      String accessKeyId, String secretAccessKey) {
    }

    /**
     * Azure Key Vault. When {@code tenantId}/{@code clientId}/{@code clientSecret} are all set a
     * client-secret credential is used; otherwise {@code DefaultAzureCredential} applies
     * (workload identity, managed identity, env vars).
     */
    public record Azure(boolean enabled, String vaultUrl, String tenantId, String clientId,
                        String clientSecret) {
    }
}
