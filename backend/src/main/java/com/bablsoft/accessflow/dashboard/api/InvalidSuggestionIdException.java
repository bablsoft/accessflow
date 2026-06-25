package com.bablsoft.accessflow.dashboard.api;

/**
 * Thrown when a dashboard suggestion id is malformed, or refers to an analysis the caller does not
 * own / an item index that does not exist (AF-498). Mapped to HTTP 404.
 */
public class InvalidSuggestionIdException extends RuntimeException {

    private final String suggestionId;

    public InvalidSuggestionIdException(String suggestionId) {
        super("Invalid or unknown suggestion id: " + suggestionId);
        this.suggestionId = suggestionId;
    }

    public String suggestionId() {
        return suggestionId;
    }
}
