package com.bablsoft.accessflow.bootstrap.internal.spec;

import java.util.List;

/**
 * A bootstrap-declared review plan.
 *
 * <p>Exactly one constructor, deliberately: Spring Boot binds {@code @ConfigurationProperties}
 * records as value objects by picking a single constructor, and a second (convenience) one makes
 * that choice ambiguous — the binder then silently selects the wrong arity and leaves every
 * property unbound, so bootstrap review plans quietly stop being applied.
 */
public record ReviewPlanSpec(
        String name,
        String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        Integer minApprovalsRequired,
        Integer approvalTimeoutHours,
        Integer escalationAfterHours,
        Integer nudgeIntervalHours,
        Boolean autoApproveReads,
        List<String> notifyChannelNames,
        List<String> approverEmails
) {

    public ReviewPlanSpec {
        notifyChannelNames = notifyChannelNames == null ? List.of() : List.copyOf(notifyChannelNames);
        approverEmails = approverEmails == null ? List.of() : List.copyOf(approverEmails);
    }
}
