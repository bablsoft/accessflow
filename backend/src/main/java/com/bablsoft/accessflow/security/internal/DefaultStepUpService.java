package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.TotpVerificationService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.security.api.StepUpRequiredException;
import com.bablsoft.accessflow.security.api.StepUpService;
import com.bablsoft.accessflow.security.api.StepUpVerificationException;
import com.bablsoft.accessflow.security.internal.config.StepUpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Default {@link StepUpService}. Re-verifies an existing credential — a TOTP code when the user has
 * 2FA enrolled, otherwise the account password — and mints a single-use token via
 * {@link StepUpCodeStore}. SSO-only users without a password and without 2FA cannot step up here;
 * they complete the decision through the standard authenticated review screen instead.
 */
@Service
@RequiredArgsConstructor
class DefaultStepUpService implements StepUpService {

    private final UserQueryService userQueryService;
    private final TotpVerificationService totpVerificationService;
    private final PasswordEncoder passwordEncoder;
    private final StepUpCodeStore codeStore;
    private final StepUpProperties properties;
    private final Clock clock;

    @Override
    public StepUpToken issue(UUID userId, String email, String password, String totpCode) {
        var user = userQueryService.findByEmail(email)
                .filter(u -> u.id().equals(userId))
                .orElseThrow(StepUpVerificationException::new);
        if (!verify(userId, user.passwordHash(), password, totpCode)) {
            throw new StepUpVerificationException();
        }
        var token = codeStore.issue(userId);
        return new StepUpToken(token, clock.instant().plus(properties.ttl()));
    }

    private boolean verify(UUID userId, String passwordHash, String password, String totpCode) {
        if (totpCode != null && !totpCode.isBlank()) {
            return totpVerificationService.isEnabled(userId)
                    && totpVerificationService.verify(userId, totpCode);
        }
        return password != null && !password.isBlank() && passwordHash != null
                && passwordEncoder.matches(password, passwordHash);
    }

    @Override
    public UUID consume(String token) {
        return codeStore.consume(token).orElseThrow(StepUpRequiredException::new);
    }
}
