package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountDelegatedPrincipalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * UUID first, else exact-match email (the same lookup login uses — {@code users.email} is globally
 * unique, so the organization check after the lookup is what scopes it). The grant lookup is
 * keyed on the <em>calling</em> user id: a human's personal key can never hold a grant, because
 * {@code grant} only accepts a typed service account, so it fails the same way.
 */
@Component
@RequiredArgsConstructor
class DefaultOnBehalfOfResolver implements OnBehalfOfResolver {

    private final UserQueryService userQueryService;
    private final ServiceAccountDelegatedPrincipalRepository repository;
    private final Clock clock;

    @Override
    public Optional<UUID> resolve(UUID serviceAccountUserId, UUID organizationId, String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        var value = reference.trim();
        return lookup(value)
                .filter(user -> organizationId.equals(user.organizationId()))
                .filter(UserView::active)
                .filter(user -> user.principalType() == PrincipalType.HUMAN)
                .map(UserView::id)
                .filter(principalId -> repository.findLive(serviceAccountUserId, principalId, clock.instant())
                        .isPresent());
    }

    private Optional<UserView> lookup(String value) {
        try {
            return userQueryService.findById(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return userQueryService.findByEmail(value);
        }
    }
}
