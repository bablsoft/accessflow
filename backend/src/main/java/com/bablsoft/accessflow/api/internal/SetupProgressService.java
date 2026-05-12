package com.bablsoft.accessflow.api.internal;

import java.util.UUID;

public interface SetupProgressService {

    SetupProgressView getProgress(UUID organizationId);
}
