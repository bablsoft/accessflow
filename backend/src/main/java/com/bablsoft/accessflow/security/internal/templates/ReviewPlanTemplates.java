package com.bablsoft.accessflow.security.internal.templates;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.internal.templates.ReviewPlanTemplate.ApproverDefault;
import com.bablsoft.accessflow.security.internal.templates.ReviewPlanTemplate.Defaults;

import java.util.List;

public final class ReviewPlanTemplates {

    private static final List<ReviewPlanTemplate> TEMPLATES = List.of(
            new ReviewPlanTemplate(
                    "STRICT_WRITES_2_APPROVALS",
                    "Strict — writes need 2 approvals",
                    "AI required, two reviewers must approve every write.",
                    new Defaults(
                            true,
                            true,
                            2,
                            24,
                            false,
                            List.of(
                                    new ApproverDefault(UserRoleType.REVIEWER, 1),
                                    new ApproverDefault(UserRoleType.REVIEWER, 2)
                            )
                    )
            ),
            new ReviewPlanTemplate(
                    "LENIENT_READS_AUTO_APPROVED",
                    "Lenient — reads auto-approved",
                    "Low/medium-risk reads are auto-approved; writes need one reviewer.",
                    new Defaults(
                            true,
                            true,
                            1,
                            24,
                            true,
                            List.of(new ApproverDefault(UserRoleType.REVIEWER, 1))
                    )
            ),
            new ReviewPlanTemplate(
                    "AI_ONLY_NO_HUMAN",
                    "AI-only — no human approval",
                    "AI analyzes every query; no human reviewer is required.",
                    new Defaults(
                            true,
                            false,
                            1,
                            24,
                            false,
                            List.of()
                    )
            ),
            new ReviewPlanTemplate(
                    "STANDARD_AI_PLUS_ONE_REVIEWER",
                    "Standard — AI + 1 reviewer",
                    "AI plus a single reviewer; low/medium-risk reads auto-approved.",
                    new Defaults(
                            true,
                            true,
                            1,
                            24,
                            true,
                            List.of(new ApproverDefault(UserRoleType.REVIEWER, 1))
                    )
            )
    );

    private ReviewPlanTemplates() {
    }

    public static List<ReviewPlanTemplate> all() {
        return TEMPLATES;
    }
}
