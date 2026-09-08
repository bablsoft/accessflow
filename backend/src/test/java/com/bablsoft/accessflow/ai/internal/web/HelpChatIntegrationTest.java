package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.HelpChatAnswer;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatRequest;
import com.bablsoft.accessflow.ai.api.HelpChatService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.HelpChatRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.HelpChatUnavailableException;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The user-facing help chat end to end (AF-905): create a conversation, ask, reload it, delete it.
 *
 * <p>{@link HelpChatService} is the mocked seam rather than the {@code ChatModel} underneath it. That
 * is the boundary this change owns — everything below it (retrieval, the prompt, resolving {@code [n]}
 * back to a chunk) is AF-903's and is driven by its own tests, and stubbing there would require a
 * live embedding model and a populated vector store to reach the HTTP behaviour under test. What
 * matters here is that a resolved citation survives the round trip through persistence and JSON
 * unchanged, and that a session belonging to somebody else is a 404.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpChatIntegrationTest {

    private static final String PATH = "/api/v1/help-chat";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JwtService jwtService;
    @Autowired HelpChatSessionService sessionService;
    @Autowired HelpAgentConfigRepository configRepository;
    @Autowired AiConfigRepository aiConfigRepository;

    @MockitoBean HelpChatService helpChatService;

    private MockMvcTester mvc;
    private UUID organizationId;
    private UUID userId;
    private String userToken;
    private String otherUserToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Help Chat Org " + UUID.randomUUID());
        org.setSlug("help-chat-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();
        var user = saveUser(org);
        userId = user.getId();
        userToken = generateToken(user);
        otherUserToken = generateToken(saveUser(org));
    }

    /**
     * An enabled, bound row. Sessions can only be opened where the agent could answer — nothing prunes
     * transcripts in an organization that never saved a configuration, so the endpoint refuses there.
     */
    private void enableTheAgent() {
        if (configRepository.findByOrganizationId(organizationId).isPresent()) {
            return;
        }
        var aiConfig = new AiConfigEntity();
        aiConfig.setId(UUID.randomUUID());
        aiConfig.setOrganizationId(organizationId);
        aiConfig.setName("help-chat-" + UUID.randomUUID());
        aiConfig.setProvider(AiProviderType.OPENAI);
        aiConfig.setModel("gpt-4o");
        aiConfigRepository.save(aiConfig);

        var config = new HelpAgentConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(organizationId);
        config.setEnabled(true);
        config.setAiConfigId(aiConfig.getId());
        configRepository.save(config);
    }

    /**
     * The launcher can be hidden without a failed POST: the feature being off is an answer, not an
     * error.
     */
    @Test
    void availabilityReportsDisabledInsteadOfFailingWhenTheAgentIsOff() {
        var result = mvc.get().uri(PATH + "/availability")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.retrieval_active").asBoolean().isFalse();
    }

    /**
     * Nothing prunes {@code help_chat_sessions} in an organization that never saved a help
     * configuration — the retention job's work list is the configured organizations — so an
     * unconfigured organization must not be able to open one at all.
     */
    @Test
    void startingASessionIsRefusedWhileTheAgentIsOff() {
        var result = mvc.post().uri(PATH + "/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("HELP_AGENT_DISABLED");
    }

    @Test
    void availabilityRequiresAuthentication() {
        assertThat(mvc.get().uri(PATH + "/availability").exchange()).hasStatus(401);
    }

    @Test
    void creatingASessionReturns201AndAnEmptyConversation() {
        enableTheAgent();

        var created = mvc.post().uri(PATH + "/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange();

        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.id").isNotNull();
        assertThat(created).bodyJson().extractingPath("$.message_count").asNumber().isEqualTo(0);
        assertThat(created).bodyJson().extractingPath("$.title").asString().isEmpty();
    }

    @Test
    void createAskReloadAndDeleteRoundTrip() {
        when(helpChatService.answer(any())).thenReturn(answer());

        var sessionId = createSession();

        var ask = ask(sessionId, userToken, "How do I submit a query?", "Query editor");
        assertThat(ask).hasStatus(200);
        assertThat(ask).bodyJson().extractingPath("$.session.message_count").asNumber().isEqualTo(2);
        assertThat(ask).bodyJson().extractingPath("$.assistant_message.citations[0].url").asString()
                .isEqualTo("https://accessflow.io/docs/#submitting");

        var reload = mvc.get().uri(PATH + "/sessions/" + sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange();
        assertThat(reload).hasStatus(200);
        assertThat(reload).bodyJson().extractingPath("$.session.title").asString()
                .isEqualTo("How do I submit a query?");
        assertThat(reload).bodyJson().extractingPath("$.messages").asArray().hasSize(2);
        // The citation the server resolved when the answer was produced, replayed verbatim — never
        // re-derived from the stored text (epic AF-899 decision 6).
        assertThat(reload).bodyJson().extractingPath("$.messages[1].citations[0].title").asString()
                .isEqualTo("Submitting a query");
        assertThat(reload).bodyJson().extractingPath("$.messages[1].citations[0].chunk_id").asString()
                .isEqualTo("chunk-1");
        assertThat(reload).bodyJson().extractingPath("$.messages[1].corpus_version").isNotNull();

        var listed = mvc.get().uri(PATH + "/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange();
        assertThat(listed).bodyJson().extractingPath("$.content[0].id").asString()
                .isEqualTo(sessionId);

        assertThat(mvc.delete().uri(PATH + "/sessions/" + sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()).hasStatus(204);

        assertThat(mvc.get().uri(PATH + "/sessions/" + sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()).hasStatus(404);
    }

    /**
     * 404 and never 403 — a 403 would confirm the session id exists and let one user probe another's
     * transcripts.
     */
    @Test
    void anotherUsersSessionIsNotFoundRatherThanForbidden() {
        var sessionId = createSession();

        assertThat(mvc.get().uri(PATH + "/sessions/" + sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUserToken)
                .exchange()).hasStatus(404);
        assertThat(mvc.delete().uri(PATH + "/sessions/" + sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUserToken)
                .exchange()).hasStatus(404);
        assertThat(ask(sessionId, otherUserToken, "Whose is this?", "Query editor")).hasStatus(404);
    }

    @Test
    void listSessionsShowsOnlyTheCallersOwn() {
        createSession();

        var result = mvc.get().uri(PATH + "/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUserToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(0);
    }

    @Test
    void aBlankQuestionIsRejectedWithAProblemDetail() {
        var result = ask(createSession(), userToken, "  ", "Query editor");

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void anOverlongQuestionIsRejected() {
        var result = ask(createSession(), userToken, "q".repeat(10_001), "Query editor");

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void anOverlongRouteNameIsRejected() {
        var result = ask(createSession(), userToken, "How?", "r".repeat(121));

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    /**
     * The client is supposed to send a mapped label; this asserts the server does not trust it. A
     * route that is really a URL with a query id never reaches the provider.
     */
    @Test
    void aRouteNameCarryingAQueryIdIsStrippedBeforeItReachesTheProvider() {
        when(helpChatService.answer(any())).thenReturn(answer());

        var result = ask(createSession(), userToken, "What is this screen?",
                "/queries/2f1c8a9e-4d3b-4f21-9a77-1b0e5c6d7a88");

        assertThat(result).hasStatus(200);
        var captor = ArgumentCaptor.forClass(HelpChatRequest.class);
        verify(helpChatService).answer(captor.capture());
        assertThat(captor.getValue().routeLabel()).isEmpty();
    }

    /**
     * Pins the advice ordering the whole {@code HelpChatRateLimitExceededException} subclass exists
     * for: {@code HelpChatExceptionHandler} is {@code HIGHEST_PRECEDENCE}, so the per-user limit
     * reports its own code rather than falling through to the organization-wide handler in
     * {@code AiAnalysisExceptionHandler}.
     */
    @Test
    void thePerUserRateLimitReportsItsOwnCodeRatherThanTheOrganizationWideOne() {
        when(helpChatService.answer(any()))
                .thenThrow(new HelpChatRateLimitExceededException(6, 60));

        var result = ask(createSession(), userToken, "How?", "Query editor");

        assertThat(result).hasStatus(429);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("HELP_CHAT_RATE_LIMITED");
        assertThat(result).bodyJson().extractingPath("$.limit").asNumber().isEqualTo(6);
    }

    /** The organization-wide limit keeps the shared code — the two are told apart, not merged. */
    @Test
    void theOrganizationWideRateLimitKeepsTheSharedCode() {
        when(helpChatService.answer(any())).thenThrow(new AiRateLimitExceededException(30, 60));

        var result = ask(createSession(), userToken, "How?", "Query editor");

        assertThat(result).hasStatus(429);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("AI_RATE_LIMIT_EXCEEDED");
    }

    /** Pins the 503 this endpoint documents, and that no provider text reaches the response. */
    @Test
    void aProviderFailureIsReportedWithoutLeakingItsText() {
        when(helpChatService.answer(any()))
                .thenThrow(new AiAnalysisException("connect timed out to https://api.internal:8443"));

        var result = ask(createSession(), userToken, "How?", "Query editor");

        assertThat(result).hasStatus(503);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .doesNotContain("api.internal");
    }

    @Test
    void aDisabledAgentAnswersWithConflictAndItsOwnCode() {
        when(helpChatService.answer(any()))
                .thenThrow(new HelpChatUnavailableException("error.help_chat.disabled"));

        var result = ask(createSession(), userToken, "How?", "Query editor");

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("HELP_AGENT_DISABLED");
    }

    /**
     * Created through the service rather than the endpoint: the 201 contract is asserted once, on its
     * own, and every other test wants a session id without re-parsing a response body for it.
     */
    private String createSession() {
        enableTheAgent();
        return sessionService.createSession(organizationId, userId).id().toString();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult ask(String sessionId,
                                                                           String token,
                                                                           String question,
                                                                           String routeName) {
        var body = routeName == null
                ? "{\"question\":\"" + question + "\"}"
                : "{\"question\":\"" + question + "\",\"route_name\":\"" + routeName + "\"}";
        return mvc.post().uri(PATH + "/sessions/" + sessionId + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static HelpChatAnswer answer() {
        return new HelpChatAnswer("Use the query editor [1]",
                List.of(new HelpChatCitation(1, "chunk-1", "Submitting a query", "Guides",
                        "submitting", "https://accessflow.io/docs/#submitting")),
                true, "gpt-4o", 900, 120);
    }

    private UserEntity saveUser(OrganizationEntity org) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("help-chat-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Help Chat User");
        user.setPasswordHash("hashed");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
