package com.partqam.accessflow.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedLanguageTest {

    @Test
    void resolvesEachKnownCode() {
        assertThat(SupportedLanguage.fromCode("en")).hasValue(SupportedLanguage.EN);
        assertThat(SupportedLanguage.fromCode("es")).hasValue(SupportedLanguage.ES);
        assertThat(SupportedLanguage.fromCode("de")).hasValue(SupportedLanguage.DE);
        assertThat(SupportedLanguage.fromCode("fr")).hasValue(SupportedLanguage.FR);
        assertThat(SupportedLanguage.fromCode("zh-CN")).hasValue(SupportedLanguage.ZH_CN);
        assertThat(SupportedLanguage.fromCode("ru")).hasValue(SupportedLanguage.RU);
        assertThat(SupportedLanguage.fromCode("hy")).hasValue(SupportedLanguage.HY);
    }

    @Test
    void resolvesCaseInsensitively() {
        assertThat(SupportedLanguage.fromCode("ZH-cn")).hasValue(SupportedLanguage.ZH_CN);
        assertThat(SupportedLanguage.fromCode("EN")).hasValue(SupportedLanguage.EN);
    }

    @Test
    void returnsEmptyForBlankNullOrUnknown() {
        assertThat(SupportedLanguage.fromCode(null)).isEmpty();
        assertThat(SupportedLanguage.fromCode("")).isEmpty();
        assertThat(SupportedLanguage.fromCode("   ")).isEmpty();
        assertThat(SupportedLanguage.fromCode("xx")).isEmpty();
    }

    @Test
    void displayNamesArePopulated() {
        for (var l : SupportedLanguage.values()) {
            assertThat(l.displayName()).isNotBlank();
            assertThat(l.locale()).isNotNull();
            assertThat(l.code()).isNotBlank();
        }
    }
}
