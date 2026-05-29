package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.LocalizationConfigView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackMessagesTest {

    @Mock MessageSource messageSource;
    @Mock LocalizationConfigService localizationConfigService;

    private SlackMessages messages;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messages = new SlackMessages(messageSource, localizationConfigService);
    }

    @Test
    void resolvesWithOrgDefaultLanguage() {
        when(localizationConfigService.getOrDefault(orgId)).thenReturn(view("fr"));
        when(messageSource.getMessage(eq("slack.action.approved"), any(), eq(Locale.forLanguageTag("fr"))))
                .thenReturn("Approuvé");

        assertThat(messages.forOrg(orgId, "slack.action.approved", "Alice")).isEqualTo("Approuvé");
    }

    @Test
    void fallsBackToEnglishWhenLanguageBlank() {
        when(localizationConfigService.getOrDefault(orgId)).thenReturn(view(null));
        when(messageSource.getMessage(any(), any(), eq(Locale.ENGLISH))).thenReturn("ok");

        assertThat(messages.forOrg(orgId, "slack.test.message")).isEqualTo("ok");
        verify(messageSource).getMessage(eq("slack.test.message"), any(), eq(Locale.ENGLISH));
    }

    private LocalizationConfigView view(String defaultLanguage) {
        return new LocalizationConfigView(orgId, List.of("en"), defaultLanguage, "en");
    }
}
