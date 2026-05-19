package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
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
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public UUID reconcile(OrganizationSpec spec) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.organization.name is required");
        }
        var slug = effectiveSlug(spec);
        var existing = organizationProvisioningService.findBySlug(slug);
        if (existing.isPresent()) {
            log.info("Bootstrap: organization '{}' (slug={}) already exists, skipping creation",
                    spec.name(), slug);
            return existing.get();
        }
        var orgId = organizationProvisioningService.create(spec.name(), spec.slug());
        log.info("Bootstrap: created organization '{}' (id={})", spec.name(), orgId);

        var fields = specFields(spec.name(), slug);
        stateTracker.recordFingerprintAndPublish(orgId, BootstrapResourceType.ORGANIZATION, orgId,
                fingerprinter.fingerprint(fields),
                new BootstrapResourceUpsertedEvent(
                        orgId,
                        BootstrapResourceType.ORGANIZATION,
                        orgId,
                        BootstrapChangeKind.CREATE,
                        List.of(),
                        Map.of("name", spec.name(), "slug", slug)));
        return orgId;
    }

    private static Map<String, Object> specFields(String name, String slug) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("slug", slug);
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
