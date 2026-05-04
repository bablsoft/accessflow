package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.security.api.AuthenticationService;
import com.partqam.accessflow.security.api.LoginCommand;
import com.partqam.accessflow.security.internal.web.model.LoginRequest;
import com.partqam.accessflow.security.internal.web.model.LoginResponse;
import com.partqam.accessflow.security.internal.web.model.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "JWT authentication endpoints")
@RequiredArgsConstructor
class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final int REFRESH_COOKIE_MAX_AGE = 7 * 24 * 3600;

    private final AuthenticationService authenticationService;

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password, returns JWT access token")
    @ApiResponse(responseCode = "200", description = "Login successful")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletResponse response) {
        var result = authenticationService.login(new LoginCommand(request.email(), request.password()));
        setRefreshCookie(response, result.refreshToken(), REFRESH_COOKIE_MAX_AGE);
        return ResponseEntity.ok(toLoginResponse(result));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange refresh token cookie for a new access token")
    @ApiResponse(responseCode = "200", description = "Token refreshed successfully")
    @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
    ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var result = authenticationService.refresh(refreshToken);
        setRefreshCookie(response, result.refreshToken(), REFRESH_COOKIE_MAX_AGE);
        return ResponseEntity.ok(toLoginResponse(result));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the refresh token and clear the cookie")
    @ApiResponse(responseCode = "204", description = "Logged out successfully")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        authenticationService.logout(refreshToken);
        setRefreshCookie(response, "", 0);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String value, int maxAge) {
        var cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(maxAge)
                .path("/api/v1/auth")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private LoginResponse toLoginResponse(com.partqam.accessflow.security.api.AuthResult result) {
        var user = result.user();
        var summary = new UserSummary(user.id(), user.email(), user.displayName(),
                user.role().name());
        return new LoginResponse(result.accessToken(), result.tokenType(), result.expiresIn(), summary);
    }
}
