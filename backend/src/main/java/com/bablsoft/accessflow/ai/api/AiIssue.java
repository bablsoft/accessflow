package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.RiskLevel;

public record AiIssue(RiskLevel severity, String category, String message, String suggestion) {
}
