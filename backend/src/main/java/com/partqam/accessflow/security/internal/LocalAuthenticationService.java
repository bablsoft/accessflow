package com.partqam.accessflow.security.internal;

import com.partqam.accessflow.core.api.TotpVerificationService;
import com.partqam.accessflow.core.api.UserQueryService;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.security.api.AuthResult;
import com.partqam.accessflow.security.api.AuthenticationService;
import com.partqam.accessflow.security.api.LoginCommand;
import com.partqam.accessflow.security.api.TotpAuthenticationException;
import com.partqam.accessflow.security.api.TotpRequiredException;
import com.partqam.accessflow.security.internal.config.JwtProperties;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.security.internal.jwt.JwtValidationException;
import com.partqam.accessflow.security.internal.token.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "accessflow.edition", havingValue = "community", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalAuthenticationService implements AuthenticationService {

    private final UserQueryService userQueryService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;
    private final TotpVerificationService totpVerificationService;

    @Override
    public AuthResult login(LoginCommand command) {
        var user = userQueryService.findByEmail(command.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!user.active()) {
            throw new DisabledException("Account is disabled");
        }

        if (user.passwordHash() == null
                || !passwordEncoder.matches(command.password(), user.passwordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        if (totpVerificationService.isEnabled(user.id())) {
            var totpCode = command.totpCode();
            if (totpCode == null || totpCode.isBlank()) {
                throw new TotpRequiredException("Two-factor authentication required");
            }
            if (!totpVerificationService.verify(user.id(), totpCode)) {
                throw new TotpAuthenticationException("Invalid verification code");
            }
        }

        return issueTokenPair(user);
    }

    @Override
    public AuthResult refresh(String refreshToken) {
        if (refreshTokenStore.isRevoked(refreshToken)) {
            throw new BadCredentialsException("Refresh token is invalid or expired");
        }

        var claims = parseRefreshToken(refreshToken);

        var user = userQueryService.findById(claims.userId())
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        if (!user.active()) {
            throw new DisabledException("Account is disabled");
        }

        refreshTokenStore.revoke(refreshToken);
        return issueTokenPair(user);
    }

    @Override
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokenStore.revoke(refreshToken);
        }
    }

    private AuthResult issueTokenPair(UserView user) {
        var accessToken = jwtService.generateAccessToken(user);
        var newRefreshToken = jwtService.generateRefreshToken(user);
        refreshTokenStore.store(newRefreshToken, user.id().toString(),
                jwtProperties.refreshTokenExpiry().toSeconds());
        return new AuthResult(accessToken, newRefreshToken, "Bearer",
                jwtProperties.accessTokenExpiry().toSeconds(), user);
    }

    private com.partqam.accessflow.security.api.JwtClaims parseRefreshToken(String token) {
        try {
            return jwtService.parseRefreshToken(token);
        } catch (JwtValidationException e) {
            throw new BadCredentialsException("Refresh token is invalid or expired", e);
        }
    }
}
