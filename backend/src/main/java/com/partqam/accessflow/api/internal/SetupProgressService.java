package com.partqam.accessflow.api.internal;

import java.util.UUID;

public interface SetupProgressService {

    SetupProgressView getProgress(UUID organizationId);
}
