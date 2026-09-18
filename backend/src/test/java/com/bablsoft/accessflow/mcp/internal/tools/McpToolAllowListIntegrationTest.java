package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.serviceaccounts.api.McpToolName;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountToolPolicyService;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Drives {@code tools/call} through the real stateless MCP transport with API keys (#872): a
 * service account outside its allow-list gets the structured {@code permission_denied} result and
 * the service behind the tool is never reached; a NULL list, a listed tool and a human key all
 * reach it; {@code tools/list} keeps advertising every tool. The spy on the policy pins that the
 * guard resolved the caller from the {@code SecurityContext} on the request thread — the
 * {@code immediateExecution(true)} assumption {@link GuardedToolCallback} depends on.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class McpToolAllowListIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ServiceAccountRepository serviceAccountRepository;
    @Autowired ServiceAccountProvisioningService provisioningService;
    @Autowired ApiKeyService apiKeyService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    // The test profile initializes beans lazily; the server bean is what registers the JSON-RPC
    // handler on the transport, so it has to exist before the first /mcp request.
    @Autowired McpStatelessSyncServer mcpServer;

    @MockitoBean QuerySubmissionService querySubmissionService;
    @MockitoSpyBean ServiceAccountToolPolicyService toolPolicy;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity human;
    private UserEntity bot;
    private String humanKey;
    private String botKey;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("mcp-allow-" + suffix);
        org.setSlug("mcp-allow-" + suffix);
        organizationRepository.save(org);

        human = saveUser("human-" + suffix + "@example.com");
        humanKey = apiKeyService.issue(human.getId(), org.getId(), "human", null).rawKey();

        bot = saveUser("bot-" + suffix + "@example.com");
        provisioningService.ensureRegistered(org.getId(), bot.getId(), ServiceAccountSource.UI);
        botKey = apiKeyService.issue(bot.getId(), org.getId(), "bot", null).rawKey();

        when(querySubmissionService.submit(any())).thenAnswer(inv ->
                new QuerySubmissionService.QuerySubmissionResult(UUID.randomUUID(), QueryStatus.PENDING_AI));
    }

    @AfterEach
    void cleanup() {
        // users cascades api_keys and service_accounts.
        jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", org.getId());
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", org.getId());
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", org.getId());
    }

    @Test
    void deniedToolReturnsPermissionDeniedAndNeverReachesTheService() {
        allowList("list_datasources");

        var result = callTool(botKey, "submit_query", submitArguments());

        assertThat(result).hasStatus(200);
        var body = json(result);
        assertThat(body.path("result").path("isError").asBoolean(false)).isFalse();
        var text = objectMapper.readTree(body.path("result").path("content").get(0).path("text").asString());
        assertThat(text.path("code").asString()).isEqualTo("permission_denied");
        assertThat(text.path("message").asString()).contains("submit_query");
        verifyNoInteractions(querySubmissionService);
    }

    @Test
    void emptyAllowListDeniesEverything() {
        allowList();

        var text = toolResultText(callTool(botKey, "list_datasources", "{}"));

        assertThat(objectMapper.readTree(text).path("code").asString()).isEqualTo("permission_denied");
    }

    @Test
    void listedToolReachesTheServiceAsTheServiceAccount() {
        allowList("submit_query");

        var text = toolResultText(callTool(botKey, "submit_query", submitArguments()));

        assertThat(objectMapper.readTree(text).path("status").asString()).isEqualTo("PENDING_AI");
        verify(querySubmissionService).submit(argThat(input -> input.submitterUserId().equals(bot.getId())));
    }

    @Test
    void nullAllowListAndHumanKeyAllowEveryTool() {
        assertThat(objectMapper.readTree(toolResultText(callTool(botKey, "submit_query", submitArguments())))
                .path("status").asString()).isEqualTo("PENDING_AI");
        assertThat(objectMapper.readTree(toolResultText(callTool(humanKey, "submit_query", submitArguments())))
                .path("status").asString()).isEqualTo("PENDING_AI");
        verify(querySubmissionService).submit(argThat(input -> input.submitterUserId().equals(bot.getId())));
        verify(querySubmissionService).submit(argThat(input -> input.submitterUserId().equals(human.getId())));
    }

    @Test
    void toolsListStillAdvertisesEveryToolToARestrictedCaller() {
        allowList();

        var result = mcp(botKey, """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """);

        assertThat(result).hasStatus(200);
        var tools = json(result).path("result").path("tools");
        assertThat(tools.valueStream().map(tool -> tool.path("name").asString()).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(McpToolName.values()).map(McpToolName::toolName).toList());
    }

    /**
     * The guard can only learn the caller's id from the SecurityContext of the executing thread.
     * If Spring AI ever ran tool bodies off the servlet thread, McpCurrentUser would throw, the
     * result would flip to isError=true, and the policy would never be asked with this id.
     */
    @Test
    void securityContextIsVisibleInsideTheGuardOnTheRequestThread() {
        allowList();

        var result = callTool(botKey, "submit_query", submitArguments());

        assertThat(result).hasStatus(200);
        var body = json(result);
        assertThat(body.path("result").path("isError").asBoolean(false)).isFalse();
        verify(toolPolicy).isAllowed(bot.getId(), "submit_query");
        verifyNoInteractions(querySubmissionService);
    }

    private void allowList(String... tools) {
        var account = serviceAccountRepository.findById(bot.getId()).orElseThrow();
        account.setMcpToolAllowList(tools);
        serviceAccountRepository.save(account);
    }

    private String submitArguments() {
        return "{\"datasourceId\":\"" + UUID.randomUUID() + "\",\"sql\":\"SELECT 1\"}";
    }

    private MvcTestResult callTool(String apiKey, String tool, String arguments) {
        return mcp(apiKey, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(tool, arguments));
    }

    private MvcTestResult mcp(String apiKey, String body) {
        return mvc.post().uri("/mcp")
                .header(API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body)
                .exchange();
    }

    private String toolResultText(MvcTestResult result) {
        assertThat(result).hasStatus(200);
        JsonNode body = json(result);
        assertThat(body.path("error").isMissingNode()).as(body.toString()).isTrue();
        assertThat(body.path("result").path("isError").asBoolean(false)).as(body.toString()).isFalse();
        return body.path("result").path("content").get(0).path("text").asString();
    }

    private JsonNode json(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private UserEntity saveUser(String email) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        return userRepository.save(u);
    }
}
