package com.bablsoft.accessflow.core.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * BCP-47 language tags supported by AccessFlow. The {@link #code()} matches the IETF tag and is
 * what is persisted in {@code localization_config} and {@code users.preferred_language}.
 */
public enum SupportedLanguage {
    EN("en", "English", Locale.ENGLISH),
    ES("es", "Español", Locale.of("es")),
    DE("de", "Deutsch", Locale.GERMAN),
    FR("fr", "Français", Locale.FRENCH),
    ZH_CN("zh-CN", "简体中文", Locale.SIMPLIFIED_CHINESE),
    RU("ru", "Русский", Locale.of("ru")),
    HY("hy", "Հայերեն", Locale.of("hy"));

    private final String code;
    private final String displayName;
    private final Locale locale;

    SupportedLanguage(String code, String displayName, Locale locale) {
        this.code = code;
        this.displayName = displayName;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public Locale locale() {
        return locale;
    }

    public static Optional<SupportedLanguage> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(l -> l.code.equalsIgnoreCase(code))
                .findFirst();
    }
}
