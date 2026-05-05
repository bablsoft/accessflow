package com.partqam.accessflow.core.api;

import java.util.UUID;

public record SubmitQueryCommand(
        UUID datasourceId,
        UUID submittedByUserId,
        String sqlText,
        QueryType queryType,
        String justification) {
}
