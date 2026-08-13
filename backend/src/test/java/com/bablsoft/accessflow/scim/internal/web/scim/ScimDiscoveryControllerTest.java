package com.bablsoft.accessflow.scim.internal.web.scim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScimDiscoveryControllerTest {

    private final ScimDiscoveryController controller = new ScimDiscoveryController();

    @BeforeEach
    void setUp() {
        var request = new MockHttpServletRequest("GET", "/scim/v2/ServiceProviderConfig");
        request.setServerName("af.example.com");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void serviceProviderConfigAdvertisesPatchButNoBulkSortEtag() {
        var config = controller.serviceProviderConfig();

        assertThat(config.get("schemas")).isEqualTo(
                List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        assertThat(((Map<?, ?>) config.get("patch")).get("supported")).isEqualTo(true);
        assertThat(((Map<?, ?>) config.get("bulk")).get("supported")).isEqualTo(false);
        assertThat(((Map<?, ?>) config.get("sort")).get("supported")).isEqualTo(false);
        assertThat(((Map<?, ?>) config.get("etag")).get("supported")).isEqualTo(false);
        assertThat(((Map<?, ?>) config.get("changePassword")).get("supported")).isEqualTo(false);
        assertThat(((Map<?, ?>) config.get("filter")).get("maxResults")).isEqualTo(200);
    }

    @Test
    void resourceTypesDescribeUsersAndGroups() {
        var types = controller.resourceTypes();

        assertThat(types).hasSize(2);
        assertThat(types.get(0).get("endpoint")).isEqualTo("/Users");
        assertThat(types.get(1).get("endpoint")).isEqualTo("/Groups");
    }

    @Test
    void schemasListUserAndGroup() {
        var schemas = controller.schemas();

        assertThat(schemas).extracting(m -> m.get("id")).containsExactly(
                "urn:ietf:params:scim:schemas:core:2.0:User",
                "urn:ietf:params:scim:schemas:core:2.0:Group");
    }
}
