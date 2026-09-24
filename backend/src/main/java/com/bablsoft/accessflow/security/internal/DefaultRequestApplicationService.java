package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.ApplicationNameSource;
import com.bablsoft.accessflow.core.api.ClientApplication;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.RequestApplicationService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Service
class DefaultRequestApplicationService implements RequestApplicationService {

    @Override
    public Optional<ClientApplication> current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        // A named key always wins: a caller holding it cannot relabel itself with the header.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof ApiKeyAuthentication apiKey) {
            var keyName = sanitize(apiKey.applicationName());
            if (keyName != null) {
                return Optional.of(new ClientApplication(keyName, ApplicationNameSource.API_KEY));
            }
        }
        var headerName = sanitize(attributes.getRequest().getHeader(HEADER));
        return Optional.ofNullable(headerName)
                .map(name -> new ClientApplication(name, ApplicationNameSource.HEADER));
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        var value = raw.strip();
        if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }
}
