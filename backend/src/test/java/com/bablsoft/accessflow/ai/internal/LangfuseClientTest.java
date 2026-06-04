package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LangfuseClientTest {

    private static final ResolvedLangfuseConfig CONFIG =
            new ResolvedLangfuseConfig("https://lf.example.com/", "pk-1", "sk-1", true, true);
    private static final String BASIC_AUTH = "Basic "
            + Base64.getEncoder().encodeToString("pk-1:sk-1".getBytes(StandardCharsets.UTF_8));

    private MockRestServiceServer server;
    private LangfuseClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LangfuseClient(builder.build(), JsonMapper.builder().build());
    }

    @Test
    void ingestPostsWithBasicAuth() {
        server.expect(requestTo("https://lf.example.com/api/public/ingestion"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", BASIC_AUTH))
                .andRespond(withSuccess());

        client.ingest(CONFIG, Map.of("batch", java.util.List.of()));

        server.verify();
    }

    @Test
    void fetchPromptReturnsTextPrompt() {
        server.expect(requestTo("https://lf.example.com/api/public/v2/prompts/sql-analysis?label=production"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", BASIC_AUTH))
                .andRespond(withSuccess("{\"prompt\":\"TEMPLATE {{sql}}\",\"type\":\"text\"}",
                        MediaType.APPLICATION_JSON));

        var prompt = client.fetchPrompt(CONFIG, "sql-analysis", "production");

        assertThat(prompt).contains("TEMPLATE {{sql}}");
        server.verify();
    }

    @Test
    void fetchPromptEmptyForChatPrompt() {
        server.expect(requestTo("https://lf.example.com/api/public/v2/prompts/sql-analysis?label=production"))
                .andRespond(withSuccess("{\"prompt\":[{\"role\":\"system\"}],\"type\":\"chat\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchPrompt(CONFIG, "sql-analysis", "production")).isEmpty();
    }

    @Test
    void fetchPromptEmptyForBlankTemplate() {
        server.expect(requestTo("https://lf.example.com/api/public/v2/prompts/sql-analysis?label=production"))
                .andRespond(withSuccess("{\"prompt\":\"   \",\"type\":\"text\"}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchPrompt(CONFIG, "sql-analysis", "production")).isEmpty();
    }

    @Test
    void verifyConnectionHitsProjectsEndpoint() {
        server.expect(requestTo("https://lf.example.com/api/public/projects"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.verifyConnection(CONFIG);

        server.verify();
    }

    @Test
    void verifyConnectionThrowsOnUnauthorized() {
        server.expect(requestTo("https://lf.example.com/api/public/projects"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.verifyConnection(CONFIG)).isInstanceOf(RuntimeException.class);
    }
}
