package com.bablsoft.accessflow.security.internal.config;

import com.bablsoft.accessflow.core.api.SupportedLanguage;
import com.bablsoft.accessflow.core.api.UserPreferenceService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the request locale from, in order:
 * <ol>
 *     <li>The authenticated user's persisted {@code preferred_language} (when present and supported)</li>
 *     <li>The request's {@code Accept-Language} header, intersected with {@link SupportedLanguage}</li>
 *     <li>{@link Locale#ENGLISH} as the default</li>
 * </ol>
 */
@RequiredArgsConstructor
class UserPreferenceLocaleResolver implements LocaleResolver {

    private final UserPreferenceService userPreferenceService;
    private final List<Locale> supportedLocales;
    private final Locale defaultLocale;

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        var fromUser = principalLanguage()
                .flatMap(SupportedLanguage::fromCode)
                .map(SupportedLanguage::locale);
        if (fromUser.isPresent()) {
            return fromUser.get();
        }
        var fromHeader = bestHeaderMatch(request);
        return fromHeader.orElse(defaultLocale);
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        // Stateless — UI language is persisted via PUT /me/localization
    }

    private Optional<String> principalLanguage() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtClaims claims)) {
            return Optional.empty();
        }
        try {
            return userPreferenceService.findPreferredLanguage(claims.userId());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Locale> bestHeaderMatch(HttpServletRequest request) {
        var ranges = Collections.list(request.getLocales());
        var seen = new LinkedHashSet<String>();
        for (var requested : ranges) {
            for (var supported : supportedLocales) {
                if (matches(requested, supported) && seen.add(supported.toLanguageTag())) {
                    return Optional.of(supported);
                }
            }
        }
        return Optional.empty();
    }

    private boolean matches(Locale requested, Locale supported) {
        if (requested.getLanguage().equalsIgnoreCase(supported.getLanguage())) {
            if (supported.getCountry() == null || supported.getCountry().isEmpty()) {
                return true;
            }
            return supported.getCountry().equalsIgnoreCase(requested.getCountry());
        }
        return false;
    }
}
