package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AskHelpChatCommand;
import com.bablsoft.accessflow.ai.api.HelpChatConversationService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The in-app documentation help chat, for every signed-in user (AF-905).
 *
 * <p>Gated on authentication only, like {@code /system/update-status}: the agent is a documentation
 * reader with no data access, so there is nothing here a user could learn that the product's own
 * public documentation does not already say. Whether it may run at all is an admin decision made on
 * {@code /admin/help-agent}, not a per-user permission.
 *
 * <p>Every session is scoped to the caller. One belonging to somebody else is <strong>404, never
 * 403</strong> — a transcript is private to the person who had it, and distinguishing "not yours"
 * from "not there" would let one user probe another's session ids.
 *
 * <p>Binding only: the sequencing of answer-then-store lives in {@link HelpChatConversationService},
 * and the caller's permissions and language are read from the principal and the request locale rather
 * than from the body, so a client cannot describe itself to the model as an administrator.
 */
@RestController
@RequestMapping("/api/v1/help-chat")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Help Chat",
        description = "In-app documentation help assistant, available to every signed-in user")
@RequiredArgsConstructor
class HelpChatController {

    private static final int MAX_PAGE_SIZE = 100;

    private final HelpChatConversationService conversationService;
    private final HelpChatSessionService sessionService;

    @GetMapping("/availability")
    @Operation(summary = "Report whether the help assistant can answer for the caller's organization")
    @ApiResponse(responseCode = "200",
            description = "Availability snapshot; never fails because the agent is off")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    HelpChatAvailabilityResponse availability(Authentication authentication) {
        return HelpChatAvailabilityResponse.from(
                conversationService.availability(caller(authentication).organizationId()));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List the caller's help conversations, most recently active first")
    @ApiResponse(responseCode = "200", description = "Page of conversations without their messages")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    HelpChatSessionPageResponse listSessions(Authentication authentication,
                                             @PageableDefault(size = 20) Pageable pageable) {
        var caller = caller(authentication);
        var page = sessionService.listSessions(caller.organizationId(), caller.userId(),
                capped(SpringPageableAdapter.toPageRequest(pageable)));
        return HelpChatSessionPageResponse.from(page.map(HelpChatSessionResponse::from));
    }

    @PostMapping("/sessions")
    @Operation(summary = "Start an empty help conversation")
    @ApiResponse(responseCode = "201", description = "The new, empty conversation")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    ResponseEntity<HelpChatSessionResponse> createSession(Authentication authentication) {
        var caller = caller(authentication);
        HelpChatSessionView created = sessionService.createSession(caller.organizationId(),
                caller.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(HelpChatSessionResponse.from(created));
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Read one of the caller's conversations with all its messages")
    @ApiResponse(responseCode = "200", description = "The conversation, messages oldest first")
    @ApiResponse(responseCode = "404",
            description = "No such conversation for this caller — also what another user's session returns")
    HelpChatConversationResponse getSession(@PathVariable UUID id, Authentication authentication) {
        var caller = caller(authentication);
        return HelpChatConversationResponse.from(
                sessionService.loadConversation(caller.organizationId(), caller.userId(), id));
    }

    @PostMapping("/sessions/{id}/messages")
    @Operation(summary = "Ask a question in a conversation and store the answer")
    @ApiResponse(responseCode = "200", description = "The stored turn — question and answer")
    @ApiResponse(responseCode = "400", description = "The question is blank or too long")
    @ApiResponse(responseCode = "404", description = "No such conversation for this caller")
    @ApiResponse(responseCode = "409", description = "The help assistant is off or unbound")
    @ApiResponse(responseCode = "429", description = "Per-user or organization rate limit exceeded")
    @ApiResponse(responseCode = "503", description = "The AI provider call failed")
    HelpChatTurnResponse ask(@PathVariable UUID id,
                             @Valid @RequestBody AskHelpChatRequest body,
                             Authentication authentication,
                             Locale locale) {
        var caller = caller(authentication);
        return HelpChatTurnResponse.from(conversationService.ask(new AskHelpChatCommand(
                caller.organizationId(), caller.userId(), id, body.question(), body.routeName(),
                permissionNames(caller), locale == null ? null : locale.toLanguageTag())));
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Delete one of the caller's conversations and its messages")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such conversation for this caller")
    ResponseEntity<Void> deleteSession(@PathVariable UUID id, Authentication authentication) {
        var caller = caller(authentication);
        sessionService.deleteSession(caller.organizationId(), caller.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clamps rather than rejects: an oversized {@code size} is a client that wants everything, not an
     * error worth a 400, and the ceiling is what the API contract promises.
     */
    private static PageRequest capped(PageRequest pageRequest) {
        return pageRequest.size() <= MAX_PAGE_SIZE
                ? pageRequest
                : new PageRequest(pageRequest.page(), MAX_PAGE_SIZE, pageRequest.sort());
    }

    private static List<String> permissionNames(JwtClaims caller) {
        return caller.permissions() == null
                ? List.of()
                : caller.permissions().stream().map(Permission::name).sorted().toList();
    }

    private static JwtClaims caller(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
