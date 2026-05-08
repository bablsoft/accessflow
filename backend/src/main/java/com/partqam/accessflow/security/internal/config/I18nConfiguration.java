package com.partqam.accessflow.security.internal.config;

import com.partqam.accessflow.core.api.SupportedLanguage;
import com.partqam.accessflow.core.api.UserPreferenceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration
class I18nConfiguration {

    @Bean
    LocaleResolver localeResolver(UserPreferenceService userPreferenceService) {
        var supported = Arrays.stream(SupportedLanguage.values())
                .map(SupportedLanguage::locale)
                .toList();
        return new UserPreferenceLocaleResolver(userPreferenceService, List.copyOf(supported), Locale.ENGLISH);
    }
}
