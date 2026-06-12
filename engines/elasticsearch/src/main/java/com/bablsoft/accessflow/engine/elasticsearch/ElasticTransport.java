package com.bablsoft.accessflow.engine.elasticsearch;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link SearchTransport} backed by the Elastic low-level {@code org.elasticsearch.client.RestClient}
 * (Apache HttpComponents 4) — the {@code db_type=ELASTICSEARCH} flavour. Raw JSON in, raw JSON out;
 * driver failures become a {@link SearchTransportException}. The low-level client (unlike the typed
 * {@code ElasticsearchClient}) enforces no product check and sends no version-compat media type, so
 * the engine controls every header.
 */
final class ElasticTransport implements SearchTransport {

    private static final Logger log = LoggerFactory.getLogger(ElasticTransport.class);

    private final RestClient client;

    ElasticTransport(EsConnectionConfig cfg) {
        var builder = RestClient.builder(new HttpHost(cfg.host(), cfg.port(), cfg.scheme()))
                .setHttpClientConfigCallback(httpClient -> {
                    if (cfg.auth() instanceof EsAuth.Basic basic) {
                        var provider = new BasicCredentialsProvider();
                        provider.setCredentials(AuthScope.ANY,
                                new UsernamePasswordCredentials(basic.username(), basic.password()));
                        httpClient.setDefaultCredentialsProvider(provider);
                    }
                    if (cfg.trustAll()) {
                        httpClient.setSSLContext(EsTls.trustAllContext());
                        httpClient.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                    }
                    return httpClient;
                })
                .setRequestConfigCallback(rc -> rc
                        .setConnectTimeout((int) cfg.connectTimeout().toMillis())
                        .setSocketTimeout((int) cfg.socketTimeout().toMillis()));
        if (cfg.auth() instanceof EsAuth.ApiKey apiKey) {
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader("Authorization", "ApiKey " + apiKey.token())});
        }
        this.client = builder.build();
    }

    @Override
    public String perform(String method, String path, Map<String, String> params, String body,
                          String contentType) {
        var request = new Request(method, path);
        params.forEach(request::addParameter);
        if (body != null) {
            request.setEntity(new NStringEntity(body,
                    ContentType.create(contentType, StandardCharsets.UTF_8)));
        }
        try {
            Response response = client.performRequest(request);
            return bodyOf(response.getEntity());
        } catch (ResponseException re) {
            throw new SearchTransportException(
                    re.getResponse().getStatusLine().getStatusCode(),
                    bodyOf(re.getResponse().getEntity()), false, re);
        } catch (InterruptedIOException te) {
            throw new SearchTransportException(0, null, true, te);
        } catch (IOException io) {
            throw new SearchTransportException(0, null, false, io);
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            log.warn("Failed to close Elasticsearch REST client: {}", e.getMessage());
        }
    }

    private static String bodyOf(HttpEntity entity) {
        if (entity == null) {
            return "";
        }
        try {
            return EntityUtils.toString(entity, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
