package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.bootstrap.internal.reconcile.AdminUserReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.AiConfigReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.DatasourceReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.NotificationChannelReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.OAuth2Reconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.OrganizationReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.ReviewPlanReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.SamlReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.SystemSmtpReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapRunner {

    private final BootstrapProperties properties;
    private final OrganizationReconciler organizationReconciler;
    private final AdminUserReconciler adminUserReconciler;
    private final NotificationChannelReconciler notificationChannelReconciler;
    private final AiConfigReconciler aiConfigReconciler;
    private final ReviewPlanReconciler reviewPlanReconciler;
    private final DatasourceReconciler datasourceReconciler;
    private final SamlReconciler samlReconciler;
    private final OAuth2Reconciler oauth2Reconciler;
    private final SystemSmtpReconciler systemSmtpReconciler;

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void run() {
        if (!properties.enabled()) {
            log.debug("Bootstrap: accessflow.bootstrap.enabled=false, skipping");
            return;
        }
        log.info("Bootstrap: starting env-driven admin config reconciliation");

        UUID organizationId;
        try {
            organizationId = organizationReconciler.reconcile(properties.organization());
        } catch (RuntimeException ex) {
            throw new BootstrapException(List.of(
                    "organization: " + ex.getMessage()));
        }

        var errors = new ArrayList<String>();
        runStep(errors, "admin",
                () -> adminUserReconciler.reconcile(organizationId, properties.admin()));

        Map<String, UUID> notificationChannelsByName = Map.of();
        try {
            notificationChannelsByName = notificationChannelReconciler.reconcile(
                    organizationId, properties.notificationChannels());
        } catch (RuntimeException ex) {
            errors.add("notificationChannels: " + ex.getMessage());
            log.error("Bootstrap: notificationChannels reconciliation failed", ex);
        }

        Map<String, UUID> aiConfigsByName = Map.of();
        try {
            aiConfigsByName = aiConfigReconciler.reconcile(organizationId, properties.aiConfigs());
        } catch (RuntimeException ex) {
            errors.add("aiConfigs: " + ex.getMessage());
            log.error("Bootstrap: aiConfigs reconciliation failed", ex);
        }

        Map<String, UUID> reviewPlansByName = Map.of();
        try {
            reviewPlansByName = reviewPlanReconciler.reconcile(
                    organizationId, properties.reviewPlans(), notificationChannelsByName);
        } catch (RuntimeException ex) {
            errors.add("reviewPlans: " + ex.getMessage());
            log.error("Bootstrap: reviewPlans reconciliation failed", ex);
        }

        var finalReviewPlans = reviewPlansByName;
        var finalAiConfigs = aiConfigsByName;
        runStep(errors, "datasources",
                () -> datasourceReconciler.reconcile(
                        organizationId, properties.datasources(), finalReviewPlans, finalAiConfigs));
        runStep(errors, "saml",
                () -> samlReconciler.reconcile(organizationId, properties.saml()));
        runStep(errors, "oauth2",
                () -> oauth2Reconciler.reconcile(organizationId, properties.oauth2()));
        runStep(errors, "systemSmtp",
                () -> systemSmtpReconciler.reconcile(organizationId, properties.systemSmtp()));

        if (!errors.isEmpty()) {
            throw new BootstrapException(errors);
        }
        log.info("Bootstrap: reconciliation completed successfully for organization {}", organizationId);
    }

    private void runStep(List<String> errors, String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException ex) {
            errors.add(name + ": " + ex.getMessage());
            log.error("Bootstrap: {} reconciliation failed", name, ex);
        }
    }
}
