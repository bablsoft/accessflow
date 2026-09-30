package com.bablsoft.accessflow.notifications.internal;

import java.util.UUID;

public record RecipientView(UUID userId, String email, String displayName) {
}
