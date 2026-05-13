package com.bablsoft.accessflow.security.internal.config;

import com.bablsoft.accessflow.security.internal.filter.ApiKeyAuthenticationFilter;
import com.bablsoft.accessflow.security.internal.filter.JwtAuthenticationFilter;
import com.bablsoft.accessflow.security.internal.oauth2.DynamicClientRegistrationRepository;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2LoginFailureHandler;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2LoginSuccessHandler;
import com.bablsoft.accessflow.security.internal.web.SecurityExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final SecurityExceptionHandler securityExceptionHandler;
    private final CorsProperties corsProperties;

    /**
     * Dedicated chain for the OAuth2 redirect dance. The provider returns to {@code /callback/*}
     * with a code; Spring's OAuth2LoginAuthenticationFilter exchanges it, populates the
     * authentication, then our success handler issues a one-time exchange code and redirects to
     * the frontend. Sessions are needed transiently for the auth-request state — they're not
     * relied on for anything else.
     */
    @Bean
    @Order(1)
    SecurityFilterChain oauth2FilterChain(HttpSecurity http,
                                          DynamicClientRegistrationRepository clientRegistrationRepository,
                                          OAuth2LoginSuccessHandler successHandler,
                                          OAuth2LoginFailureHandler failureHandler) throws Exception {
        http
                .securityMatcher("/api/v1/auth/oauth2/authorize/**",
                        "/api/v1/auth/oauth2/callback/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(o -> o
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .authorizationEndpoint(a -> a.baseUri("/api/v1/auth/oauth2/authorize"))
                        .redirectionEndpoint(r -> r.baseUri("/api/v1/auth/oauth2/callback/*"))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        .contentTypeOptions(c -> {})
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityExceptionHandler)
                        .accessDeniedHandler(securityExceptionHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/api/v1/auth/setup", "/api/v1/auth/setup-status",
                                "/api/v1/auth/oauth2/providers", "/api/v1/auth/oauth2/exchange",
                                "/api/v1/auth/saml/enabled").permitAll()
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // WebSocket handshake is authenticated by JwtHandshakeInterceptor via
                        // ?token= (browsers cannot set Authorization on the WS upgrade).
                        .requestMatchers("/ws").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(corsProperties.allowedOrigin()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-API-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
