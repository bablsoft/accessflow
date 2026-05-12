package com.bablsoft.accessflow.core.api;

public final class UnsupportedLanguageException extends LocalizationConfigException {

    private final String code;

    public UnsupportedLanguageException(String code) {
        super("Unsupported language code: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
