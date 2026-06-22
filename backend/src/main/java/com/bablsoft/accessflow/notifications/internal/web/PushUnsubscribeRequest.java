package com.bablsoft.accessflow.notifications.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushUnsubscribeRequest(
        @NotBlank(message = "{validation.push.endpoint_required}")
        @Size(max = 2048, message = "{validation.push.endpoint_size}")
        String endpoint) {
}
