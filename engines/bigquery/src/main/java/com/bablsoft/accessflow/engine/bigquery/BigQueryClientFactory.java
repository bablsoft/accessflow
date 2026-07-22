package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.http.HttpTransportOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds a native {@link BigQuery} client from a {@link DatasourceConnectionDescriptor} — the
 * single place that maps AccessFlow connection fields onto the google-cloud-bigquery client.
 * BigQuery's "connection" is cloud credentials plus a project, not host/port:
 * {@code database_name} is {@code project} or {@code project.dataset} (the optional dataset pins
 * the default dataset unqualified table names resolve against), the decrypted
 * {@code password_encrypted} is the service-account key JSON, and {@code jdbc_url_override} an
 * optional custom endpoint (the BigQuery emulator) — when set, the client authenticates with
 * {@link NoCredentials} instead of a service account. Credentials are decrypted only here, at
 * client construction time, mirroring the host rule that plaintext lives no longer than pool
 * init. Invalid key JSON raises {@link IllegalArgumentException} carrying the resolved
 * {@code error.bigquery.invalid_credentials} message, surfaced by the probe / executor.
 */
final class BigQueryClientFactory {

    /** The parsed {@code database_name}: a GCP project id plus an optional default dataset. */
    record ProjectTarget(String projectId, String defaultDataset) {

        static ProjectTarget parse(String databaseName) {
            var raw = databaseName == null ? "" : databaseName.strip();
            int dot = raw.indexOf('.');
            return dot < 0
                    ? new ProjectTarget(raw, null)
                    : new ProjectTarget(raw.substring(0, dot), raw.substring(dot + 1));
        }
    }

    private final CredentialDecryptor credentials;
    private final BigQueryEngineSettings settings;
    private final EngineMessages messages;

    BigQueryClientFactory(CredentialDecryptor credentials, BigQueryEngineSettings settings,
                          EngineMessages messages) {
        this.credentials = credentials;
        this.settings = settings;
        this.messages = messages;
    }

    BigQuery open(DatasourceConnectionDescriptor descriptor) {
        return options(descriptor).getService();
    }

    /** Assembles the client options; separated from {@link #open} so tests need no network. */
    BigQueryOptions options(DatasourceConnectionDescriptor descriptor) {
        var target = ProjectTarget.parse(descriptor.databaseName());
        var transport = HttpTransportOptions.newBuilder()
                .setConnectTimeout((int) settings.connectTimeout().toMillis())
                .setReadTimeout((int) settings.readTimeout().toMillis())
                .build();
        var builder = BigQueryOptions.newBuilder()
                .setProjectId(target.projectId())
                .setTransportOptions(transport);
        var endpoint = descriptor.jdbcUrlOverride();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.setHost(endpoint.strip()).setCredentials(NoCredentials.getInstance());
        } else {
            builder.setCredentials(serviceAccountCredentials(descriptor));
        }
        return builder.build();
    }

    private ServiceAccountCredentials serviceAccountCredentials(
            DatasourceConnectionDescriptor descriptor) {
        var keyJson = credentials.decrypt(descriptor.passwordEncrypted());
        try {
            return ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(keyJson.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    messages.get("error.bigquery.invalid_credentials"), e);
        }
    }
}
