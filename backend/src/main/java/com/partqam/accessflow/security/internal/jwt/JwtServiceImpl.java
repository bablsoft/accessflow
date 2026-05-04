package com.partqam.accessflow.security.internal.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.security.api.JwtClaims;
import com.partqam.accessflow.security.internal.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class JwtServiceImpl implements JwtService {

    private static final String ISSUER = "accessflow";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ORG_ID = "org_id";
    private static final JOSEObjectType TYPE_ACCESS = new JOSEObjectType("access");
    private static final JOSEObjectType TYPE_REFRESH = new JOSEObjectType("refresh");

    private final RSAPrivateKey rsaPrivateKey;
    private final RSAPublicKey rsaPublicKey;
    private final JwtProperties jwtProperties;

    @Override
    public String generateAccessToken(UserView user) {
        return buildToken(user, TYPE_ACCESS, jwtProperties.accessTokenExpiry().toSeconds());
    }

    @Override
    public String generateRefreshToken(UserView user) {
        return buildToken(user, TYPE_REFRESH, jwtProperties.refreshTokenExpiry().toSeconds());
    }

    @Override
    public JwtClaims parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS);
    }

    @Override
    public JwtClaims parseRefreshToken(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private String buildToken(UserView user, JOSEObjectType type, long ttlSeconds) {
        var now = Instant.now();
        var expiry = now.plusSeconds(ttlSeconds);

        var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(type)
                .build();

        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_ORG_ID, user.organizationId().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        var jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(rsaPrivateKey));
        } catch (JOSEException e) {
            throw new JwtValidationException("Failed to sign token", e);
        }
        return jwt.serialize();
    }

    private JwtClaims parse(String token, JOSEObjectType expectedType) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new JwtValidationException("Invalid token format", e);
        }

        try {
            if (!jwt.verify(new RSASSAVerifier(rsaPublicKey))) {
                throw new JwtValidationException("Token signature invalid");
            }
        } catch (JOSEException e) {
            throw new JwtValidationException("Token verification failed", e);
        }

        JWTClaimsSet claimsSet;
        try {
            claimsSet = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new JwtValidationException("Failed to parse token claims", e);
        }

        if (claimsSet.getExpirationTime() == null
                || claimsSet.getExpirationTime().toInstant().isBefore(Instant.now())) {
            throw new JwtValidationException("Token expired");
        }

        var actualType = jwt.getHeader().getType();
        if (!expectedType.equals(actualType)) {
            throw new JwtValidationException("Token type mismatch: expected %s".formatted(expectedType.getType()));
        }

        try {
            return new JwtClaims(
                    UUID.fromString(claimsSet.getSubject()),
                    claimsSet.getStringClaim(CLAIM_EMAIL),
                    UserRoleType.valueOf(claimsSet.getStringClaim(CLAIM_ROLE)),
                    UUID.fromString(claimsSet.getStringClaim(CLAIM_ORG_ID))
            );
        } catch (ParseException | IllegalArgumentException e) {
            throw new JwtValidationException("Failed to extract claims", e);
        }
    }
}
