package com.bablsoft.accessflow.engine.elasticsearch;

/**
 * The authentication strategy resolved once per datasource at client-build time. HTTP basic
 * (username + decrypted password) and Elasticsearch/OpenSearch API key
 * ({@code Authorization: ApiKey <base64>}) are the two supported modes; {@link None} covers an
 * unauthenticated cluster. The seam keeps the transport construction auth-agnostic.
 */
sealed interface EsAuth permits EsAuth.Basic, EsAuth.ApiKey, EsAuth.None {

    record Basic(String username, String password) implements EsAuth {
    }

    record ApiKey(String token) implements EsAuth {
    }

    enum None implements EsAuth {
        INSTANCE
    }
}
