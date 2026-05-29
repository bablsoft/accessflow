package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Resolves Slack-facing message keys in the organization's default language so ephemeral replies
 * and updated messages are localized the same way as the rest of the product.
 */
@Component
@RequiredArgsConstructor
public class SlackMessages {

    private final MessageSource messageSource;
    private final LocalizationConfigService localizationConfigService;

    public String forOrg(UUID organizationId, String key, Object... args) {
        return messageSource.getMessage(key, args, localeForOrg(organizationId));
    }

    private Locale localeForOrg(UUID organizationId) {
        var lang = localizationConfigService.getOrDefault(organizationId).defaultLanguage();
        if (lang == null || lang.isBlank()) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag(lang);
    }
}
