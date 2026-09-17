package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.ApiKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.security.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.security.api.ApiKeyNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysExceptionHandlerTest {

    private final MessageSource messages = staticMessages();
    private final ApiKeysExceptionHandler handler = new ApiKeysExceptionHandler(messages);

    @Test
    void not_found_maps_to_404() {
        var pd = handler.handleNotFound(new ApiKeyNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_KEY_NOT_FOUND");
        assertThat(pd.getDetail()).isEqualTo("API key not found");
    }

    @Test
    void duplicate_name_maps_to_409() {
        var pd = handler.handleDuplicateName(new ApiKeyDuplicateNameException("ci"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_KEY_DUPLICATE_NAME");
    }

    @Test
    void bootstrap_declared_maps_to_409_with_the_key_id() {
        var keyId = UUID.randomUUID();
        var pd = handler.handleBootstrapDeclared(new ApiKeyBootstrapDeclaredException(keyId));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "API_KEY_BOOTSTRAP_DECLARED");
        assertThat(pd.getProperties()).containsEntry("apiKeyId", keyId.toString());
        assertThat(pd.getProperties()).containsKey("timestamp");
        assertThat(pd.getDetail()).isEqualTo("declared");
    }

    private static MessageSource staticMessages() {
        var source = new StaticMessageSource();
        source.addMessage("error.api_key.not_found", Locale.getDefault(), "API key not found");
        source.addMessage("error.api_key.duplicate_name", Locale.getDefault(),
                "An API key with that name already exists. Pick a different name.");
        source.addMessage("error.api_key.bootstrap_declared", Locale.getDefault(), "declared");
        return source;
    }
}
