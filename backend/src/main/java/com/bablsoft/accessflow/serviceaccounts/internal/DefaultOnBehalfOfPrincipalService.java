package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.OnBehalfOfPrincipalService;
import com.bablsoft.accessflow.serviceaccounts.internal.web.ApiKeyRequestFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the attribute {@link ApiKeyRequestFilter} set after validating the header (#874). Off the
 * request thread there are no request attributes and the answer is empty — which is exactly what
 * an after-commit listener or a scheduled job should see.
 */
@Service
class DefaultOnBehalfOfPrincipalService implements OnBehalfOfPrincipalService {

    @Override
    public Optional<UUID> current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attributes.getAttribute(ApiKeyRequestFilter.ON_BEHALF_OF_ATTRIBUTE,
                        RequestAttributes.SCOPE_REQUEST))
                .filter(UUID.class::isInstance)
                .map(UUID.class::cast);
    }
}
