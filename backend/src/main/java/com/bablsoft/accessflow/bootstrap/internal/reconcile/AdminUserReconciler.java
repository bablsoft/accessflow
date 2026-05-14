package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.AdminSpec;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserReconciler {

    private final UserQueryService userQueryService;
    private final UserAdminService userAdminService;
    private final PasswordEncoder passwordEncoder;

    public UUID reconcile(UUID organizationId, AdminSpec spec) {
        if (spec == null) {
            throw new IllegalStateException("accessflow.bootstrap.admin is required");
        }
        if (spec.email() == null || spec.email().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.admin.email is required");
        }
        if (spec.displayName() == null || spec.displayName().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.admin.displayName is required");
        }
        if (spec.password() == null || spec.password().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.admin.password is required");
        }

        var existing = userQueryService.findByEmail(spec.email());
        if (existing.isPresent()) {
            var user = existing.get();
            if (!user.organizationId().equals(organizationId)) {
                throw new IllegalStateException(
                        "Admin email '%s' is registered against a different organization".formatted(spec.email()));
            }
            if (passwordEncoder.matches(spec.password(), user.passwordHash())) {
                log.debug("Bootstrap: admin '{}' present with matching password, no change", spec.email());
            } else {
                log.info("Bootstrap: admin '{}' present, password differs from spec — leaving the stored hash intact"
                        + " (use the admin API to rotate)", spec.email());
            }
            return user.id();
        }

        var created = userAdminService.createUser(new CreateUserCommand(
                organizationId,
                spec.email(),
                spec.displayName(),
                passwordEncoder.encode(spec.password()),
                UserRoleType.ADMIN));
        log.info("Bootstrap: created admin user '{}' (id={})", created.email(), created.id());
        return created.id();
    }

    public UUID lookupId(UUID organizationId, String email) {
        return userQueryService.findByEmail(email)
                .filter(u -> u.organizationId().equals(organizationId))
                .map(UserView::id)
                .orElseThrow(() -> new IllegalStateException(
                        "Bootstrap reference to user '%s' did not resolve in organization %s"
                                .formatted(email, organizationId)));
    }
}
