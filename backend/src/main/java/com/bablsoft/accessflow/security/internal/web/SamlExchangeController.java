package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.AuthenticationService;
import com.bablsoft.accessflow.security.internal.saml.SamlExchangeCodeStore;
import com.bablsoft.accessflow.security.internal.web.model.LoginResponse;
import com.bablsoft.accessflow.security.internal.web.model.SamlExchangeRequest;
import com.bablsoft.accessflow.security.internal.web.model.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trades the one-time exchange code produced by the SAML success handler for an AccessFlow
 * JWT pair (access token + refresh cookie). Codes are single-use and short-lived; an invalid or
 * already-consumed code returns 401.
 */
@RestController
@RequestMapping("/api/v1/auth/saml/exchange")
@Tag(name = "Authentication", description = "Exchange SAML redirect code for AccessFlow JWT pair")
@RequiredArgsConstructor
class SamlExchangeController {

    private final SamlExchangeCodeStore exchangeCodeStore;
    private final AuthenticationService authenticationService;
    private final RefreshCookieWriter refreshCookieWriter;
    private final MessageSource messageSource;

    @PostMapping
    @Operation(summary = "Exchange a one-time SAML code for an access token")
    @ApiResponse(responseCode = "200", description = "Login successful; refresh cookie set")
    @ApiResponse(responseCode = "400", description = "Validation error (missing code)")
    @ApiResponse(responseCode = "401", description = "Code missing, expired, or already consumed")
    @SecurityRequirements
    ResponseEntity<LoginResponse> exchange(@Valid @RequestBody SamlExchangeRequest request,
                                           HttpServletResponse response) {
        var userId = exchangeCodeStore.consume(request.code())
                .orElseThrow(() -> new BadCredentialsException(messageSource.getMessage(
                        "error.saml.exchange_code_invalid", null,
                        LocaleContextHolder.getLocale())));
        var result = authenticationService.issueForUser(userId);
        refreshCookieWriter.write(response, result.refreshToken(),
                RefreshCookieWriter.REFRESH_COOKIE_MAX_AGE);
        var user = result.user();
        var summary = new UserSummary(user.id(), user.email(), user.displayName(),
                user.role().name(), user.authProvider().name(), user.totpEnabled(),
                user.preferredLanguage());
        return ResponseEntity.ok(new LoginResponse(result.accessToken(), result.tokenType(),
                result.expiresIn(), summary));
    }
}
