package com.partqam.accessflow.ai.api;

import com.partqam.accessflow.core.api.RiskLevel;

public record AiIssue(RiskLevel severity, String category, String message, String suggestion) {
}
