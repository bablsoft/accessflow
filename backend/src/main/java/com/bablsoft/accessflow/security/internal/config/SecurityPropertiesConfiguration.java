package com.bablsoft.accessflow.security.internal.config;

import com.bablsoft.accessflow.security.internal.oauth2.OAuth2RedirectProperties;
import com.bablsoft.accessflow.security.internal.saml.SamlRedirectProperties;
import com.bablsoft.accessflow.security.internal.saml.SamlSpKeyMaterialProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class, OAuth2RedirectProperties.class,
        SamlRedirectProperties.class, SamlSpKeyMaterialProperties.class,
        InvitationProperties.class, PasswordResetProperties.class})
class SecurityPropertiesConfiguration {

    @Bean
    RSAPrivateKey rsaPrivateKey(JwtProperties props) throws GeneralSecurityException {
        return RsaKeyLoader.loadPrivateKey(props.privateKey());
    }

    @Bean
    RSAPublicKey rsaPublicKey(RSAPrivateKey rsaPrivateKey) throws GeneralSecurityException {
        return RsaKeyLoader.derivePublicKey(rsaPrivateKey);
    }
}
