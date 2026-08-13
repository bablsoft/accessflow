package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The authenticated identity of a SCIM request (#621): a per-org bearer token, not a user. The
 * single {@code SCIM} authority never overlaps the {@code PERM_*}/{@code ROLE_*} space, so a SCIM
 * token can never reach a JWT-guarded endpoint even if a request escaped the /scim/v2 chain.
 */
public class ScimAuthenticationToken extends AbstractAuthenticationToken {

    private final ScimPrincipal principal;

    public ScimAuthenticationToken(ScimPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("SCIM")));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public ScimPrincipal getPrincipal() {
        return principal;
    }
}
