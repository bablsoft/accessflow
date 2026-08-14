package com.bablsoft.accessflow.scim.internal.config;

import com.bablsoft.accessflow.scim.internal.web.scim.ScimAuthenticationEntryPoint;
import com.bablsoft.accessflow.scim.internal.web.scim.ScimTokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The {@code /scim/v2/**} security chain (#621). {@code @Order(0)} wins over the security
 * module's chains (1 = SAML, 2 = OAuth2, 3 = catch-all) so SCIM traffic never reaches the JWT
 * filter or the ProblemDetail-emitting entry point. CORS is deliberately disabled: SCIM is
 * server-to-server from the IdP's provisioning engine — a browser never calls it (same reasoning
 * as the SAML chain).
 */
@Configuration(proxyBeanMethods = false)
class ScimSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain scimFilterChain(HttpSecurity http,
                                        ScimTokenAuthenticationFilter tokenFilter,
                                        ScimAuthenticationEntryPoint entryPoint) throws Exception {
        http
                .securityMatcher("/scim/v2/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
