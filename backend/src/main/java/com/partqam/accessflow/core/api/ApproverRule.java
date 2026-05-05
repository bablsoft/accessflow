package com.partqam.accessflow.core.api;

import java.util.UUID;

/**
 * One row in a review plan's approver list. Either {@code userId} or {@code role} (or both)
 * is set per the {@code review_plan_approvers} schema.
 */
public record ApproverRule(UUID userId, UserRoleType role, int stage) {
}
