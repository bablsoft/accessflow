package com.bablsoft.accessflow.requestgroups.internal.web;

import jakarta.validation.constraints.Size;

record GroupDecisionRequest(
        @Size(max = 2000, message = "{validation.group_decision.comment.size}")
        String comment) {
}
