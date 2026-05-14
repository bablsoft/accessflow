package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record SubmitQueryCommand(
        UUID datasourceId,
        UUID submittedByUserId,
        String sqlText,
        QueryType queryType,
        boolean transactional,
        String justification) {
}
