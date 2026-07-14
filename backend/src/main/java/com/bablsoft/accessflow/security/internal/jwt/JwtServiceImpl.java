package com.bablsoft.accessflow.security.internal.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RolePermissionResolver;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class JwtServiceImpl implements JwtService {

    private static final String ISSUER = "accessflow";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ROLE_ID = "role_id";
    private static final String CLAIM_ROLE_NAME = "role_name";
    private static final String CLAIM_PERMISSIONS = "permissions";
    private static final String CLAIM_ORG_ID = "org_id";
    private static final String CLAIM_PLATFORM_ADMIN = "platform_admin";
    private static final JOSEObjectType TYPE_ACCESS = new JOSEObjectType("access");
    private static final JOSEObjectType TYPE_REFRESH = new JOSEObjectType("refresh");

    private final RSAPrivateKey rsaPrivateKey;
    private final RSAPublicKey rsaPublicKey;
    private final JwtProperties jwtProperties;
    private final RolePermissionResolver rolePermissionResolver;

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

        var builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ORG_ID, user.organizationId().toString())
                .claim(CLAIM_PLATFORM_ADMIN, user.platformAdmin())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry));
        if (user.role() != null) {
            builder.claim(CLAIM_ROLE, user.role().name());
        }
        if (user.roleId() != null) {
            builder.claim(CLAIM_ROLE_ID, user.roleId().toString());
        }
        if (user.roleName() != null) {
            builder.claim(CLAIM_ROLE_NAME, user.roleName());
        }
        if (TYPE_ACCESS.equals(type)) {
            // The access token bakes in the role's resolved permission set (AF-522); role edits
            // propagate on the next refresh (15-minute TTL). Refresh tokens stay permission-free —
            // the fresh access token minted on refresh re-resolves.
            var permissions = rolePermissionResolver.resolve(user.roleId(), user.role());
            builder.claim(CLAIM_PERMISSIONS,
                    permissions.stream().map(Permission::name).sorted().toList());
        }
        var claims = builder.build();

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
            var roleClaim = claimsSet.getStringClaim(CLAIM_ROLE);
            var role = roleClaim != null ? UserRoleType.valueOf(roleClaim) : null;
            var roleIdClaim = claimsSet.getStringClaim(CLAIM_ROLE_ID);
            var roleNameClaim = claimsSet.getStringClaim(CLAIM_ROLE_NAME);
            return new JwtClaims(
                    UUID.fromString(claimsSet.getSubject()),
                    claimsSet.getStringClaim(CLAIM_EMAIL),
                    role,
                    roleIdClaim != null ? UUID.fromString(roleIdClaim) : null,
                    roleNameClaim != null ? roleNameClaim : (role != null ? role.name() : null),
                    parsePermissions(claimsSet, role),
                    UUID.fromString(claimsSet.getStringClaim(CLAIM_ORG_ID)),
                    Boolean.TRUE.equals(claimsSet.getBooleanClaim(CLAIM_PLATFORM_ADMIN))
            );
        } catch (ParseException | IllegalArgumentException e) {
            throw new JwtValidationException("Failed to extract claims", e);
        }
    }

    /**
     * Extracts the permissions claim; a token minted before AF-522 has none, so the set is
     * derived from the (then-mandatory) system-role claim instead.
     */
    private static Set<Permission> parsePermissions(JWTClaimsSet claimsSet, UserRoleType role)
            throws ParseException {
        var names = claimsSet.getStringListClaim(CLAIM_PERMISSIONS);
        if (names == null) {
            return role != null ? SystemRolePermissions.of(role) : Set.of();
        }
        if (names.isEmpty()) {
            return Set.of();
        }
        var permissions = EnumSet.noneOf(Permission.class);
        for (var name : names) {
            permissions.add(Permission.valueOf(name));
        }
        return Set.copyOf(permissions);
    }
}
