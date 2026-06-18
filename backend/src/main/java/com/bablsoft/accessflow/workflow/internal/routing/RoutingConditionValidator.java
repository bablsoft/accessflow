package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link ConditionNode} tree at policy create / update time for syntax the pure value
 * model cannot enforce in its constructors without reaching into {@code workflow.internal} — today
 * just the CIDR syntax of every {@link ConditionNode.SourceIpMatches} leaf (delegated to
 * {@link CidrMatcher}, same package). Throws {@link IllegalRoutingPolicyException} (HTTP 422) on the
 * first malformed entry. Combinators are walked recursively; other leaves need no extra validation.
 */
@Component
public class RoutingConditionValidator {

    private final MessageSource messageSource;

    public RoutingConditionValidator(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public void validate(ConditionNode node) {
        if (node == null) {
            return;
        }
        switch (node) {
            case ConditionNode.And and -> and.children().forEach(this::validate);
            case ConditionNode.Or or -> or.children().forEach(this::validate);
            case ConditionNode.Not not -> validate(not.child());
            case ConditionNode.SourceIpMatches sourceIp -> validateCidrs(sourceIp);
            default -> { /* no additional validation for other leaves */ }
        }
    }

    private void validateCidrs(ConditionNode.SourceIpMatches sourceIp) {
        for (String cidr : sourceIp.cidrs()) {
            if (!CidrMatcher.isValidCidr(cidr)) {
                throw new IllegalRoutingPolicyException(
                        messageSource.getMessage("error.routing_policy_cidr_invalid",
                                new Object[] {cidr}, LocaleContextHolder.getLocale()));
            }
        }
    }
}
