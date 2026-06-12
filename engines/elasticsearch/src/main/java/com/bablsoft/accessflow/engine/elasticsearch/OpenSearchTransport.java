package com.bablsoft.accessflow.engine.elasticsearch;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link SearchTransport} backed by the OpenSearch low-level {@code org.opensearch.client.RestClient}
 * (Apache HttpComponents 5) — the {@code db_type=OPENSEARCH} flavour. Functionally identical to
 * {@link ElasticTransport}, but OpenSearch's 3.x low-level client moved to HttpClient 5
 * ({@code org.apache.hc}) while Elasticsearch's stayed on HttpClient 4 ({@code org.apache.http}), so
 * the two cannot share the HttpComponents wiring even though the shared engine logic, REST endpoints
 * and JSON shapes are wire-compatible. Both clients live in one shaded JAR (each HttpComponents
 * major relocated separately) so the {@code elasticsearch} and {@code opensearch} connectors share
 * every other class.
 */
final class OpenSearchTransport implements SearchTransport {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchTransport.class);

    private final RestClient client;

    OpenSearchTransport(EsConnectionConfig cfg) {
        var builder = RestClient.builder(new HttpHost(cfg.scheme(), cfg.host(), cfg.port()))
                .setHttpClientConfigCallback(httpClient -> {
                    if (cfg.auth() instanceof EsAuth.Basic basic) {
                        var provider = new BasicCredentialsProvider();
                        provider.setCredentials(
                                new AuthScope(new HttpHost(cfg.scheme(), cfg.host(), cfg.port())),
                                new UsernamePasswordCredentials(basic.username(),
                                        basic.password().toCharArray()));
                        httpClient.setDefaultCredentialsProvider(provider);
                    }
                    var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                    .setConnectTimeout(Timeout.ofMilliseconds(
                                            cfg.connectTimeout().toMillis()))
                                    .build());
                    if (cfg.trustAll()) {
                        connectionManager.setTlsStrategy(ClientTlsStrategyBuilder.create()
                                .setSslContext(EsTls.trustAllContext())
                                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                .build());
                    }
                    httpClient.setConnectionManager(connectionManager.build());
                    return httpClient;
                })
                .setRequestConfigCallback(rc -> rc
                        .setResponseTimeout(Timeout.ofMilliseconds(cfg.socketTimeout().toMillis()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                                cfg.connectTimeout().toMillis())));
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
            request.setEntity(new StringEntity(body,
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
            log.warn("Failed to close OpenSearch REST client: {}", e.getMessage());
        }
    }

    private static String bodyOf(HttpEntity entity) {
        if (entity == null) {
            return "";
        }
        try {
            return EntityUtils.toString(entity, StandardCharsets.UTF_8);
        } catch (IOException | ParseException | RuntimeException e) {
            return "";
        }
    }
}
