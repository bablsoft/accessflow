package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.PushSubscriptionService;
import com.bablsoft.accessflow.notifications.api.PushSubscriptionService.PushSubscriptionCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-user Web Push subscription management (AF-444). All endpoints require an authenticated user;
 * subscriptions are always scoped to the caller. The VAPID public key is non-secret and any
 * authenticated user may read it to subscribe.
 */
@RestController
@RequestMapping("/api/v1/push")
@Tag(name = "Web Push", description = "PWA push-subscription management (AF-444)")
@RequiredArgsConstructor
class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;

    @GetMapping("/vapid-public-key")
    @Operation(summary = "Return the deployment VAPID public key used to create a push subscription")
    @ApiResponse(responseCode = "200", description = "Public key returned")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    VapidPublicKeyResponse vapidPublicKey() {
        return new VapidPublicKeyResponse(pushSubscriptionService.vapidPublicKey());
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Store (or refresh) the caller's push subscription for this device")
    @ApiResponse(responseCode = "201", description = "Subscription stored")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    void subscribe(@Valid @RequestBody PushSubscriptionRequest body, Authentication authentication) {
        var caller = currentClaims(authentication);
        pushSubscriptionService.subscribe(new PushSubscriptionCommand(
                caller.userId(),
                caller.organizationId(),
                body.endpoint(),
                body.keys().p256dh(),
                body.keys().auth(),
                body.userAgent()));
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the caller's push subscription for this device")
    @ApiResponse(responseCode = "204", description = "Subscription removed (idempotent)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    void unsubscribe(@Valid @RequestBody PushUnsubscribeRequest body, Authentication authentication) {
        var caller = currentClaims(authentication);
        pushSubscriptionService.unsubscribe(caller.userId(), body.endpoint());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
