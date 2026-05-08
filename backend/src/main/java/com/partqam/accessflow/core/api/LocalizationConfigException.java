package com.partqam.accessflow.core.api;

public sealed class LocalizationConfigException extends RuntimeException
        permits UnsupportedLanguageException, LanguageNotInAllowedListException,
                IllegalLocalizationConfigException {

    protected LocalizationConfigException(String message) {
        super(message);
    }
}
