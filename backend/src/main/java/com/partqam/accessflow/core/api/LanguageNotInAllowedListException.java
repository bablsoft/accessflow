package com.partqam.accessflow.core.api;

public final class LanguageNotInAllowedListException extends LocalizationConfigException {

    private final String code;

    public LanguageNotInAllowedListException(String code) {
        super("Language is not in the organization's allowed languages: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
