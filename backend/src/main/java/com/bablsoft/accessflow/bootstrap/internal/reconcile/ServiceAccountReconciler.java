package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.ServiceAccountSpec;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconciles CI / IaC service accounts (AF-452): a user authenticated only by a declared API key,
 * used by the Terraform provider and the reusable CI Actions. Mirrors {@link AiConfigReconciler}'s
 * fingerprint-skip / authoritative-upsert flow, keyed on the service-account user id. The supplied
 * raw API key is imported (hash stored) via {@link ApiKeyService#importOrUpdate}; password login is
 * disabled by seeding an unusable random hash. Since #868 the account is also a typed identity:
 * on every create or update it is registered through
 * {@link ServiceAccountProvisioningService#ensureRegistered} as {@code SERVICE_ACCOUNT} /
 * {@code managed_by = BOOTSTRAP}. The UI-owned fields of that row (tool allow-list, rate limits,
 * owner, description) are deliberately outside the spec and its fingerprint, so an admin's edit
 * survives a restart and an existing install adopts the feature with no YAML change. A declared
 * email that already belongs to a plain user is adopted (typed on the next changed reconcile) and
 * logged at WARN, since there is no reverse path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceAccountReconciler {

    private final UserQueryService userQueryService;
    private final UserAdminService userAdminService;
    private final ApiKeyService apiKeyService;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;
    private final ServiceAccountProvisioningService serviceAccountProvisioningService;

    public Map<String, UUID> reconcile(UUID organizationId, List<ServiceAccountSpec> specs) {
        var byEmail = new HashMap<String, UUID>();
        for (var spec : specs) {
            byEmail.put(spec.email(), applyOne(organizationId, spec));
        }
        return Map.copyOf(byEmail);
    }

    private UUID applyOne(UUID organizationId, ServiceAccountSpec spec) {
        if (isBlank(spec.email())) {
            throw new IllegalStateException("Service account spec is missing 'email'");
        }
        if (isBlank(spec.displayName())) {
            throw new IllegalStateException(
                    "Service account '%s' is missing 'displayName'".formatted(spec.email()));
        }
        if (isBlank(spec.apiKeyName())) {
            throw new IllegalStateException(
                    "Service account '%s' is missing 'apiKeyName'".formatted(spec.email()));
        }
        if (isBlank(spec.apiKey())) {
            throw new IllegalStateException(
                    "Service account '%s' is missing 'apiKey'".formatted(spec.email()));
        }
        var role = spec.role() == null ? UserRoleType.ADMIN : spec.role();

        var userId = resolveOrCreateUser(organizationId, spec, role);

        var specFingerprint = fingerprinter.fingerprint(specFields(spec, role));
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.SERVICE_ACCOUNT, userId)
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: service account '{}' unchanged, skipping API key import", spec.email());
            return userId;
        }

        var changeKind = storedFingerprint == null ? BootstrapChangeKind.CREATE : BootstrapChangeKind.UPDATE;
        // Type the identity before the key import: a failed import must leave a correctly typed
        // user rather than a keyed HUMAN.
        serviceAccountProvisioningService.ensureRegistered(organizationId, userId,
                ServiceAccountSource.BOOTSTRAP);
        apiKeyService.importOrUpdate(userId, organizationId, spec.apiKeyName(), spec.apiKey(),
                spec.apiKeyExpiresAt());
        log.info("Bootstrap: {} service-account API key '{}' for '{}' (userId={})",
                changeKind == BootstrapChangeKind.CREATE ? "imported" : "updated",
                spec.apiKeyName(), spec.email(), userId);

        // Metadata intentionally omits the raw key; the api_key_name + email are the identifiers.
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.SERVICE_ACCOUNT,
                userId, specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.SERVICE_ACCOUNT,
                        userId,
                        changeKind,
                        List.of(),
                        Map.of("email", spec.email(), "api_key_name", spec.apiKeyName(), "role", role.name())));
        return userId;
    }

    private UUID resolveOrCreateUser(UUID organizationId, ServiceAccountSpec spec, UserRoleType role) {
        var existing = userQueryService.findByEmail(spec.email());
        if (existing.isPresent()) {
            var user = existing.get();
            if (!user.organizationId().equals(organizationId)) {
                throw new IllegalStateException(
                        "Service account email '%s' is registered against a different organization"
                                .formatted(spec.email()));
            }
            if (user.principalType() != PrincipalType.SERVICE_ACCOUNT) {
                // Adoption is deliberate (a pre-#868 install declares accounts that already exist
                // as plain users), but a typo naming a real person's email must be loud: since #869
                // that person can no longer sign in interactively (password, refresh, or SSO).
                log.warn("Bootstrap: service account '{}' matches an existing {} user {} — "
                        + "adopting it as a SERVICE_ACCOUNT; interactive sign-in for it will be blocked",
                        spec.email(), user.principalType(), user.id());
            }
            return user.id();
        }
        var created = userAdminService.createUser(new CreateUserCommand(
                organizationId,
                spec.email(),
                spec.displayName(),
                // Service accounts authenticate by API key only — seed an unusable random hash so
                // password login is impossible.
                passwordEncoder.encode(UUID.randomUUID().toString()),
                role,
                false));
        log.info("Bootstrap: created service-account user '{}' (id={}, role={})",
                created.email(), created.id(), role);
        return created.id();
    }

    private static Map<String, Object> specFields(ServiceAccountSpec spec, UserRoleType role) {
        var map = new LinkedHashMap<String, Object>();
        map.put("email", spec.email());
        map.put("display_name", spec.displayName());
        map.put("role", role.name());
        map.put("api_key_name", spec.apiKeyName());
        map.put("api_key", spec.apiKey());
        map.put("api_key_expires_at", spec.apiKeyExpiresAt() == null ? null : spec.apiKeyExpiresAt().toString());
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
