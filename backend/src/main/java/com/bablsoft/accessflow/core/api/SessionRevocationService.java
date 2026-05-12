package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public interface SessionRevocationService {

    void revokeAllSessions(UUID userId);
}
