package com.partqam.accessflow.security.internal.jwt;

import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.security.internal.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceImplTest {

    private JwtServiceImpl jwtService;
    private UserView testUser;

    @BeforeEach
    void setUp() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var privateKey = (RSAPrivateKey) keyPair.getPrivate();
        var publicKey = (RSAPublicKey) keyPair.getPublic();

        var props = new JwtProperties("", Duration.ofMinutes(15), Duration.ofDays(7));
        jwtService = new JwtServiceImpl(privateKey, publicKey, props);

        testUser = new UserView(
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                UserRoleType.ANALYST,
                UUID.randomUUID(),
                true,
                AuthProviderType.LOCAL,
                null,
                null,
                null,
                false,
                null
        );
    }

    @Test
    void accessTokenRoundtripPreservesClaims() {
        var token = jwtService.generateAccessToken(testUser);
        var claims = jwtService.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(testUser.id());
        assertThat(claims.email()).isEqualTo(testUser.email());
        assertThat(claims.role()).isEqualTo(testUser.role());
        assertThat(claims.organizationId()).isEqualTo(testUser.organizationId());
    }

    @Test
    void refreshTokenRoundtripPreservesClaims() {
        var token = jwtService.generateRefreshToken(testUser);
        var claims = jwtService.parseRefreshToken(token);

        assertThat(claims.userId()).isEqualTo(testUser.id());
        assertThat(claims.email()).isEqualTo(testUser.email());
    }

    @Test
    void accessTokenRejectedWhenParsedAsRefresh() {
        var token = jwtService.generateAccessToken(testUser);

        assertThatThrownBy(() -> jwtService.parseRefreshToken(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("type mismatch");
    }

    @Test
    void refreshTokenRejectedWhenParsedAsAccess() {
        var token = jwtService.generateRefreshToken(testUser);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("type mismatch");
    }

    @Test
    void expiredAccessTokenThrows() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var expiredProps = new JwtProperties("", Duration.ofSeconds(-1), Duration.ofDays(7));
        var expiredService = new JwtServiceImpl(
                (RSAPrivateKey) keyPair.getPrivate(),
                (RSAPublicKey) keyPair.getPublic(),
                expiredProps);

        var token = expiredService.generateAccessToken(testUser);

        assertThatThrownBy(() -> expiredService.parseAccessToken(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() throws Exception {
        var otherKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var wrongPublicKey = (RSAPublicKey) otherKeyPair.getPublic();

        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var privateKey = (RSAPrivateKey) keyPair.getPrivate();
        var props = new JwtProperties("", Duration.ofMinutes(15), Duration.ofDays(7));
        var signerService = new JwtServiceImpl(privateKey, wrongPublicKey, props);

        var token = signerService.generateAccessToken(testUser);

        assertThatThrownBy(() -> signerService.parseAccessToken(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void malformedTokenThrows() {
        assertThatThrownBy(() -> jwtService.parseAccessToken("not.a.jwt"))
                .isInstanceOf(JwtValidationException.class);
    }
}
