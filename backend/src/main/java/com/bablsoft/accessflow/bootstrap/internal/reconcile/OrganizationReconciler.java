package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrganizationReconciler {

    private final OrganizationProvisioningService organizationProvisioningService;

    public UUID reconcile(OrganizationSpec spec) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.organization.name is required");
        }
        var existing = organizationProvisioningService.findBySlug(effectiveSlug(spec));
        if (existing.isPresent()) {
            log.info("Bootstrap: organization '{}' (slug={}) already exists, skipping creation",
                    spec.name(), effectiveSlug(spec));
            return existing.get();
        }
        var orgId = organizationProvisioningService.create(spec.name(), spec.slug());
        log.info("Bootstrap: created organization '{}' (id={})", spec.name(), orgId);
        return orgId;
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
