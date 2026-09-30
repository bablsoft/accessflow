package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.PrincipalType;

import java.util.UUID;

public record RecipientView(UUID userId, String email, String displayName,
                            PrincipalType principalType) {

    /** A recipient whose principal type is unknown is treated as a person. */
    public RecipientView(UUID userId, String email, String displayName) {
        this(userId, email, displayName, PrincipalType.HUMAN);
    }
}
