package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;

public record GeneratedSqlResponse(
        String sql,
        AiProviderType aiProvider,
        String aiModel,
        int promptTokens,
        int completionTokens,
        String syntax) {

    public static GeneratedSqlResponse from(GeneratedSqlResult result) {
        return new GeneratedSqlResponse(
                result.sql(),
                result.aiProvider(),
                result.aiModel(),
                result.promptTokens(),
                result.completionTokens(),
                result.syntax());
    }
}
