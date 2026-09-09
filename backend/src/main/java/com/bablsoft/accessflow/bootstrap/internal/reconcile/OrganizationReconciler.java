package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrganizationReconciler {

    private final OrganizationProvisioningService organizationProvisioningService;
    private final OrganizationAdminService organizationAdminService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public UUID reconcile(OrganizationSpec spec) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.organization.name is required");
        }
        var slug = effectiveSlug(spec);
        var specFields = specFields(spec, slug);
        var specFingerprint = fingerprinter.fingerprint(specFields);
        var existing = organizationProvisioningService.findBySlug(slug);
        if (existing.isPresent()) {
            var orgId = existing.get();
            log.info("Bootstrap: organization '{}' (slug={}) already exists, skipping creation",
                    spec.name(), slug);
            applyGovernanceDomains(orgId, spec, specFingerprint, specFields);
            return orgId;
        }
        var orgId = organizationProvisioningService.create(spec.name(), spec.slug());
        log.info("Bootstrap: created organization '{}' (id={})", spec.name(), orgId);
        if (spec.governsApis() != null || spec.governsDeployments() != null) {
            writeGovernanceDomains(orgId, spec);
        }

        stateTracker.recordFingerprintAndPublish(orgId, BootstrapResourceType.ORGANIZATION, orgId,
                specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        orgId,
                        BootstrapResourceType.ORGANIZATION,
                        orgId,
                        BootstrapChangeKind.CREATE,
                        List.of(),
                        Map.of("name", spec.name(), "slug", slug)));
        return orgId;
    }

    /**
     * Re-applies the declared governance-domain hints to an organization that already exists — but
     * only when the declared spec itself changed, which is the same fingerprint short-circuit every
     * other reconciler uses. Without it this would be the one bootstrap write that runs on every
     * restart: it would bump {@code organizations.updated_at} for nothing, emit no audit event, and
     * silently revert a change an admin had just made through
     * {@code PUT /admin/governance-domains} — with nothing in the log to explain why their
     * navigation came back.
     */
    private void applyGovernanceDomains(UUID orgId, OrganizationSpec spec, String specFingerprint,
                                        Map<String, Object> specFields) {
        if (spec.governsApis() == null && spec.governsDeployments() == null) {
            return;
        }
        var storedFingerprint = stateTracker
                .findFingerprint(orgId, BootstrapResourceType.ORGANIZATION, orgId)
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: organization '{}' unchanged, leaving governance domains alone",
                    spec.name());
            return;
        }
        writeGovernanceDomains(orgId, spec);
        log.info("Bootstrap: updated governance domains on organization '{}' (id={})",
                spec.name(), orgId);
        stateTracker.recordFingerprintAndPublish(orgId, BootstrapResourceType.ORGANIZATION, orgId,
                specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        orgId,
                        BootstrapResourceType.ORGANIZATION,
                        orgId,
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(Map.of(), specFields),
                        Map.of("name", spec.name(), "slug", effectiveSlug(spec))));
    }

    /** A null flag leaves the stored value alone; every other column is left untouched. */
    private void writeGovernanceDomains(UUID orgId, OrganizationSpec spec) {
        organizationAdminService.update(orgId, new UpdateOrganizationCommand(
                null, null, null, null, spec.governsApis(), spec.governsDeployments()));
    }

    /**
     * The declared spec, fingerprinted. The two governance-domain flags are part of it (#926) —
     * leaving them out would make the fingerprint blind to the only field this reconciler can
     * update, so a changed declaration would never be applied to an existing organization.
     */
    private static Map<String, Object> specFields(OrganizationSpec spec, String slug) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", spec.name());
        map.put("slug", slug);
        map.put("governs_apis", spec.governsApis());
        map.put("governs_deployments", spec.governsDeployments());
        return map;
    }

    private static String effectiveSlug(OrganizationSpec spec) {
        return spec.slug() == null || spec.slug().isBlank() ? slugify(spec.name()) : spec.slug();
    }

    private static String slugify(String input) {
        var lowered = input == null ? "" : input.toLowerCase();
        var sanitized = lowered
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return sanitized.isEmpty() ? "org" : sanitized;
    }
}
