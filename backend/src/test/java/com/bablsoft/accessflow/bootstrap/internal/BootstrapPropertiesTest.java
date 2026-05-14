package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.bootstrap.internal.spec.AdminSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapPropertiesTest {

    @Test
    void disabledByDefault() {
        var props = bind(Map.of());

        assertThat(props.enabled()).isFalse();
        assertThat(props.reviewPlans()).isEmpty();
        assertThat(props.aiConfigs()).isEmpty();
        assertThat(props.datasources()).isEmpty();
        assertThat(props.notificationChannels()).isEmpty();
        assertThat(props.oauth2()).isEmpty();
    }

    @Test
    void bindsTopLevelOrganizationAndAdmin() {
        var values = new LinkedHashMap<String, Object>();
        values.put("accessflow.bootstrap.enabled", "true");
        values.put("accessflow.bootstrap.organization.name", "Acme");
        values.put("accessflow.bootstrap.organization.slug", "acme");
        values.put("accessflow.bootstrap.admin.email", "admin@acme.com");
        values.put("accessflow.bootstrap.admin.display-name", "Initial Admin");
        values.put("accessflow.bootstrap.admin.password", "s3cret");

        var props = bind(values);

        assertThat(props.enabled()).isTrue();
        assertThat(props.organization()).isEqualTo(new OrganizationSpec("Acme", "acme"));
        assertThat(props.admin()).isEqualTo(new AdminSpec("admin@acme.com", "Initial Admin", "s3cret"));
    }

    @Test
    void bindsIndexedDatasourceList() {
        var values = new LinkedHashMap<String, Object>();
        values.put("accessflow.bootstrap.enabled", "true");
        values.put("accessflow.bootstrap.datasources[0].name", "prod-pg");
        values.put("accessflow.bootstrap.datasources[0].db-type", "POSTGRESQL");
        values.put("accessflow.bootstrap.datasources[0].host", "pg.prod");
        values.put("accessflow.bootstrap.datasources[0].port", "5432");
        values.put("accessflow.bootstrap.datasources[0].database-name", "app");
        values.put("accessflow.bootstrap.datasources[0].username", "reader");
        values.put("accessflow.bootstrap.datasources[0].password", "pw");
        values.put("accessflow.bootstrap.datasources[0].review-plan-name", "standard");

        var props = bind(values);

        assertThat(props.datasources()).hasSize(1);
        var ds = props.datasources().get(0);
        assertThat(ds.name()).isEqualTo("prod-pg");
        assertThat(ds.host()).isEqualTo("pg.prod");
        assertThat(ds.port()).isEqualTo(5432);
        assertThat(ds.reviewPlanName()).isEqualTo("standard");
    }

    @Test
    void bindsReviewPlanWithApproverEmailsAndNotifyChannels() {
        var values = new LinkedHashMap<String, Object>();
        values.put("accessflow.bootstrap.enabled", "true");
        values.put("accessflow.bootstrap.review-plans[0].name", "standard");
        values.put("accessflow.bootstrap.review-plans[0].notify-channel-names[0]", "ops");
        values.put("accessflow.bootstrap.review-plans[0].notify-channel-names[1]", "security");
        values.put("accessflow.bootstrap.review-plans[0].approver-emails[0]", "admin@acme.com");

        var props = bind(values);

        assertThat(props.reviewPlans()).hasSize(1);
        var rp = props.reviewPlans().get(0);
        assertThat(rp.notifyChannelNames()).containsExactly("ops", "security");
        assertThat(rp.approverEmails()).containsExactly("admin@acme.com");
    }

    private static BootstrapProperties bind(Map<String, Object> values) {
        var source = new MapConfigurationPropertySource(values);
        return new Binder(source)
                .bind("accessflow.bootstrap", BootstrapProperties.class)
                .orElse(new BootstrapProperties(false, null, null, null, null, null, null, null, null, null));
    }
}
