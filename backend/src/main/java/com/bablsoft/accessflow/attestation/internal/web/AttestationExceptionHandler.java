package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationItemNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationReviewerNotEligibleException;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationScopeException;
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

// Higher precedence than the security module's GlobalExceptionHandler, whose Exception.class
// catch-all would otherwise win the resolution race for these specific attestation exceptions.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class AttestationExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(AttestationCampaignNotFoundException.class)
    ProblemDetail handleCampaignNotFound(AttestationCampaignNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.attestation_campaign_not_found"),
                "ATTESTATION_CAMPAIGN_NOT_FOUND");
    }

    @ExceptionHandler(AttestationItemNotFoundException.class)
    ProblemDetail handleItemNotFound(AttestationItemNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.attestation_item_not_found"),
                "ATTESTATION_ITEM_NOT_FOUND");
    }

    @ExceptionHandler(AttestationReviewerNotEligibleException.class)
    ProblemDetail handleNotEligible(AttestationReviewerNotEligibleException ex) {
        return problem(HttpStatus.FORBIDDEN, msg("error.attestation_reviewer_not_eligible"),
                "ATTESTATION_REVIEWER_NOT_ELIGIBLE");
    }

    @ExceptionHandler(IllegalAttestationCampaignTransitionException.class)
    ProblemDetail handleInvalidState(IllegalAttestationCampaignTransitionException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.attestation_campaign_invalid_state"),
                "ATTESTATION_CAMPAIGN_INVALID_STATE");
        if (ex.currentStatus() != null) {
            pd.setProperty("currentStatus", ex.currentStatus().name());
        }
        return pd;
    }

    @ExceptionHandler(IllegalAttestationScopeException.class)
    ProblemDetail handleInvalidScope(IllegalAttestationScopeException ex) {
        return problem(HttpStatus.BAD_REQUEST, msg("error.attestation_invalid_scope"),
                "ATTESTATION_INVALID_SCOPE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
