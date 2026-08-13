package com.bablsoft.accessflow.scim.internal.web.scim;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ScimAuthenticationEntryPointTest {

    @Test
    void writes401ScimErrorEnvelope() throws Exception {
        var entryPoint = new ScimAuthenticationEntryPoint(new ObjectMapper());
        var response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("nope"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/scim+json");
        assertThat(response.getContentAsString())
                .contains("urn:ietf:params:scim:api:messages:2.0:Error")
                .contains("\"status\":\"401\"");
    }
}
