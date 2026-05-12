package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.internal.UserNotificationService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
@Tag(name = "User Notifications", description = "In-app notification inbox for the authenticated user")
@RequiredArgsConstructor
class UserNotificationController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final UserNotificationService service;
    private final ObjectMapper objectMapper;

    @GetMapping
    @Operation(summary = "List in-app notifications for the caller, newest first")
    @ApiResponse(responseCode = "200", description = "Paginated notification feed")
    UserNotificationPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        var caller = currentClaims(authentication);
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var view = service.listForUser(caller.userId(), pageable)
                .map(v -> UserNotificationResponse.from(v, objectMapper));
        return UserNotificationPageResponse.from(view);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Return the number of unread notifications for the caller")
    @ApiResponse(responseCode = "200", description = "Unread count")
    UnreadCountResponse unreadCount(Authentication authentication) {
        var caller = currentClaims(authentication);
        return new UnreadCountResponse(service.unreadCountForUser(caller.userId()));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark a single notification as read")
    @ApiResponse(responseCode = "204", description = "Notification marked as read")
    @ApiResponse(responseCode = "404", description = "Notification not found in caller's inbox")
    ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        service.markRead(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all unread notifications for the caller as read")
    @ApiResponse(responseCode = "204", description = "All notifications marked as read")
    ResponseEntity<Void> markAllRead(Authentication authentication) {
        var caller = currentClaims(authentication);
        service.markAllRead(caller.userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a notification from the caller's inbox")
    @ApiResponse(responseCode = "204", description = "Notification deleted")
    @ApiResponse(responseCode = "404", description = "Notification not found in caller's inbox")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        service.delete(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
