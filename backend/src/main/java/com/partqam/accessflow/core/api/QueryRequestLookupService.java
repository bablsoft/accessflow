package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

public interface QueryRequestLookupService {

    Optional<QueryRequestSnapshot> findById(UUID queryRequestId);
}
