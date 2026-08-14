package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.scim.internal.protocol.ScimError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 401 in the SCIM error envelope (#621) — IdP provisioning engines parse
 * {@code urn:ietf:params:scim:api:messages:2.0:Error}, not RFC 9457 ProblemDetail, so the shared
 * {@code SecurityExceptionHandler} must not answer on this chain.
 */
@Component
@RequiredArgsConstructor
public class ScimAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(ScimMediaTypes.SCIM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ScimError.of(401, null, "Invalid or missing SCIM bearer token")));
    }
}
