package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AskHelpChatCommand;
import com.bablsoft.accessflow.ai.api.HelpChatAvailabilityView;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatConversationService;
import com.bablsoft.accessflow.ai.api.HelpChatConversationView;
import com.bablsoft.accessflow.ai.api.HelpChatMessageView;
import com.bablsoft.accessflow.ai.api.HelpChatRole;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionView;
import com.bablsoft.accessflow.ai.api.HelpChatTurnView;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelpChatControllerTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            new JwtClaims(userId, "analyst@example.com", UserRoleType.ANALYST, null, "ANALYST",
                    Set.of(Permission.QUERY_REVIEW, Permission.QUERY_SUBMIT_SELECT), organizationId, false),
            "n/a", List.of());

    private HelpChatConversationService conversationService;
    private HelpChatSessionService sessionService;
    private HelpChatController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(HelpChatConversationService.class);
        sessionService = mock(HelpChatSessionService.class);
        controller = new HelpChatController(conversationService, sessionService);
    }

    @Test
    void availabilityMapsTheView() {
        when(conversationService.availability(organizationId))
                .thenReturn(new HelpChatAvailabilityView(true, false, "c0ac599ef7fc", 512));

        var response = controller.availability(authentication);

        assertThat(response.enabled()).isTrue();
        assertThat(response.retrievalActive()).isFalse();
        assertThat(response.corpusVersion()).isEqualTo("c0ac599ef7fc");
        assertThat(response.chunkCount()).isEqualTo(512);
    }

    @Test
    void listSessionsScopesToTheCallerAndMapsThePage() {
        when(sessionService.listSessions(eq(organizationId), eq(userId), any()))
                .thenReturn(new PageResponse<>(List.of(session()), 0, 20, 1L, 1));

        var response = controller.listSessions(authentication, Pageable.ofSize(20));

        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.content()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(sessionId);
                    assertThat(item.title()).isEqualTo("How do I submit a query?");
                    assertThat(item.messageCount()).isEqualTo(2);
                });
    }

    /** The contract promises at most 100 per page; an oversized ask is clamped, not refused. */
    @Test
    void listSessionsClampsAnOversizedPageSize() {
        when(sessionService.listSessions(eq(organizationId), eq(userId), any()))
                .thenReturn(PageResponse.empty(0, 100));

        controller.listSessions(authentication, Pageable.ofSize(500));

        var captor = ArgumentCaptor.forClass(com.bablsoft.accessflow.core.api.PageRequest.class);
        verify(sessionService).listSessions(eq(organizationId), eq(userId), captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(100);
    }

    @Test
    void createSessionReturns201() {
        when(sessionService.createSession(organizationId, userId)).thenReturn(session());

        var response = controller.createSession(authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(sessionId);
    }

    @Test
    void getSessionMapsMessagesAndTheirCitations() {
        when(sessionService.loadConversation(organizationId, userId, sessionId))
                .thenReturn(new HelpChatConversationView(session(),
                        List.of(message(HelpChatRole.USER, "How do I submit a query?", List.of()),
                                message(HelpChatRole.ASSISTANT, "Use the editor [1]",
                                        List.of(citation())))));

        var response = controller.getSession(sessionId, authentication);

        assertThat(response.messages()).hasSize(2);
        assertThat(response.messages().get(1).citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.index()).isEqualTo(1);
                    assertThat(citation.title()).isEqualTo("Submitting a query");
                    assertThat(citation.url()).isEqualTo("https://accessflow.io/docs/#submitting");
                });
    }

    /**
     * Permissions and language come from the principal and the request locale, never from the body:
     * a client that could name its own permissions could describe itself to the model as an admin.
     */
    @Test
    void askTakesIdentityFromThePrincipalAndNotTheBody() {
        when(conversationService.ask(any())).thenReturn(new HelpChatTurnView(session(),
                message(HelpChatRole.USER, "How?", List.of()),
                message(HelpChatRole.ASSISTANT, "Like this [1]", List.of(citation()))));

        var response = controller.ask(sessionId, new AskHelpChatRequest("How?", "Review queue"),
                authentication, Locale.forLanguageTag("fr-FR"));

        var captor = ArgumentCaptor.forClass(AskHelpChatCommand.class);
        verify(conversationService).ask(captor.capture());
        var command = captor.getValue();
        assertThat(command.organizationId()).isEqualTo(organizationId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.sessionId()).isEqualTo(sessionId);
        assertThat(command.question()).isEqualTo("How?");
        assertThat(command.routeLabel()).isEqualTo("Review queue");
        assertThat(command.language()).isEqualTo("fr-FR");
        assertThat(command.permissions()).containsExactly("QUERY_REVIEW", "QUERY_SUBMIT_SELECT");
        assertThat(response.assistantMessage().citations()).hasSize(1);
    }

    @Test
    void askToleratesAMissingLocale() {
        when(conversationService.ask(any())).thenReturn(new HelpChatTurnView(session(),
                message(HelpChatRole.USER, "How?", List.of()),
                message(HelpChatRole.ASSISTANT, "Like this", List.of())));

        controller.ask(sessionId, new AskHelpChatRequest("How?", null), authentication, null);

        var captor = ArgumentCaptor.forClass(AskHelpChatCommand.class);
        verify(conversationService).ask(captor.capture());
        assertThat(captor.getValue().language()).isNull();
    }

    @Test
    void deleteSessionReturns204() {
        var response = controller.deleteSession(sessionId, authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(sessionService).deleteSession(organizationId, userId, sessionId);
    }

    private HelpChatSessionView session() {
        return new HelpChatSessionView(sessionId, organizationId, userId,
                "How do I submit a query?", 2, Instant.parse("2026-09-08T09:00:00Z"),
                Instant.parse("2026-09-08T08:00:00Z"));
    }

    private static HelpChatCitation citation() {
        return new HelpChatCitation(1, "chunk-1", "Submitting a query", "Guides", "submitting",
                "https://accessflow.io/docs/#submitting");
    }

    private static HelpChatMessageView message(HelpChatRole role, String content,
                                               List<HelpChatCitation> citations) {
        return new HelpChatMessageView(UUID.randomUUID(), role, content, citations, "c0ac599ef7fc",
                "gpt-4o", 900, 120, 850, Instant.parse("2026-09-08T09:00:00Z"));
    }
}
