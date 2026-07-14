package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * One row in a review plan's approver list. Either {@code userId} or {@code role} (or both)
 * is set per the {@code review_plan_approvers} schema. {@code role} is a role NAME — a system
 * role ("ADMIN", "REVIEWER", …) or a custom role's name — matched case-insensitively against the
 * reviewer's effective role name (AF-522).
 */
public record ApproverRule(UUID userId, String role, int stage) {
}
