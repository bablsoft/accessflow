package com.bablsoft.accessflow.apigov.internal.web;

import jakarta.validation.constraints.Size;

public record ApiDecisionRequest(
        @Size(max = 2000, message = "{validation.api_decision.comment.size}")
        String comment) {
}
