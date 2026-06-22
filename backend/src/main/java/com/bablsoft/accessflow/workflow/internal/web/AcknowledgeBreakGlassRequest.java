package com.bablsoft.accessflow.workflow.internal.web;

import jakarta.validation.constraints.Size;

/** Optional reconciliation note when acknowledging a break-glass event (AF-385). */
public record AcknowledgeBreakGlassRequest(
        @Size(max = 4000, message = "{validation.break_glass.comment_max}") String comment) {
}
