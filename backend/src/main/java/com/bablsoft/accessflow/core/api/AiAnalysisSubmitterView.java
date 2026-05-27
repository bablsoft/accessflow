package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record AiAnalysisSubmitterView(
        UUID userId,
        String email,
        String displayName,
        long count
) {
}
