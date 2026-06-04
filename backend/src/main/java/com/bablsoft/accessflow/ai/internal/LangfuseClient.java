package com.bablsoft.accessflow.ai.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Thin wrapper over the Langfuse public HTTP API, authenticated per call with the org's
 * {@code publicKey:secretKey} via HTTP Basic. Hand-rolled (no SDK) to match the notifications
 * dispatchers and keep per-org credentials out of any shared client state.
 */
@Component
class LangfuseClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    LangfuseClient(@Qualifier("langfuseRestClient") RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** POSTs a batched ingestion payload (traces + observations). Throws on transport / HTTP errors. */
    void ingest(ResolvedLangfuseConfig config, Object body) {
        var uri = resolve(config, "api/public/ingestion");
        var json = objectMapper.writeValueAsString(body);
        restClient.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(config))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Fetches a text prompt by name + label. Returns the prompt body when it is a plain-text prompt
     * containing a usable template; empty when the prompt is missing, a chat prompt, or unreadable.
     */
    Optional<String> fetchPrompt(ResolvedLangfuseConfig config, String name, String label) {
        var uri = UriComponentsBuilder.fromUriString(config.host())
                .path("api/public/v2/prompts/{name}")
                .queryParam("label", label)
                .buildAndExpand(name)
                .toUri();
        String body = restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(config))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        var root = objectMapper.readTree(body);
        var promptNode = root.path("prompt");
        if (!promptNode.isString()) {
            log.warn("Langfuse prompt '{}' (label {}) is not a text prompt; ignoring", name, label);
            return Optional.empty();
        }
        var template = promptNode.stringValue();
        return template.isBlank() ? Optional.empty() : Optional.of(template);
    }

    /** Verifies the credentials against an authenticated endpoint. Throws on failure. */
    void verifyConnection(ResolvedLangfuseConfig config) {
        var uri = resolve(config, "api/public/projects");
        restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(config))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();
    }

    private static URI resolve(ResolvedLangfuseConfig config, String path) {
        return URI.create(config.host()).resolve(path);
    }

    private static String basicAuth(ResolvedLangfuseConfig config) {
        var raw = config.publicKey() + ":" + config.secretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
