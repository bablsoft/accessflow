package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.bootstrap.internal.spec.AdminSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.AiConfigSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.DatasourceSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.NotificationChannelSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.OAuth2Spec;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.ReviewPlanSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.SamlSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.SystemSmtpSpec;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "accessflow.bootstrap")
public record BootstrapProperties(
        boolean enabled,
        @Valid OrganizationSpec organization,
        @Valid AdminSpec admin,
        @Valid List<ReviewPlanSpec> reviewPlans,
        @Valid List<AiConfigSpec> aiConfigs,
        @Valid List<DatasourceSpec> datasources,
        @Valid List<NotificationChannelSpec> notificationChannels,
        @Valid SamlSpec saml,
        @Valid List<OAuth2Spec> oauth2,
        @Valid SystemSmtpSpec systemSmtp
) {

    public BootstrapProperties {
        reviewPlans = reviewPlans == null ? List.of() : List.copyOf(reviewPlans);
        aiConfigs = aiConfigs == null ? List.of() : List.copyOf(aiConfigs);
        datasources = datasources == null ? List.of() : List.copyOf(datasources);
        notificationChannels = notificationChannels == null ? List.of() : List.copyOf(notificationChannels);
        oauth2 = oauth2 == null ? List.of() : List.copyOf(oauth2);
    }
}
