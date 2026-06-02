package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyNotFoundException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyPriorityConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

// Higher precedence than the security module's GlobalExceptionHandler so the catch-all there does
// not win the resolution race for these specific routing-policy exceptions. IllegalRoutingPolicy
// messages are already resolved/localized by the service / codec at the throw site; not-found and
// conflict are resolved here via message keys.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class RoutingPolicyExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(RoutingPolicyNotFoundException.class)
    ProblemDetail handleNotFound(RoutingPolicyNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.routing_policy_not_found"));
        pd.setProperty("error", "ROUTING_POLICY_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("policyId", ex.policyId().toString());
        return pd;
    }

    @ExceptionHandler(RoutingPolicyPriorityConflictException.class)
    ProblemDetail handlePriorityConflict(RoutingPolicyPriorityConflictException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.routing_policy_priority_conflict"));
        pd.setProperty("error", "ROUTING_POLICY_PRIORITY_CONFLICT");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("priority", ex.priority());
        return pd;
    }

    @ExceptionHandler(IllegalRoutingPolicyException.class)
    ProblemDetail handleIllegal(IllegalRoutingPolicyException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "ROUTING_POLICY_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
