package com.bablsoft.accessflow.security.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hosts the {@link PasswordEncoder} bean in its own configuration class so the broader
 * {@code SecurityConfiguration} — which depends on filters from other modules — does not
 * become a constructor-time prerequisite for every bean that needs password hashing.
 */
@Configuration
class PasswordEncoderConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
