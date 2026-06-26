package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationReviewerNotEligibleException;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationScopeException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttestationExceptionHandlerTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final AttestationExceptionHandler handler = new AttestationExceptionHandler(messageSource);

    AttestationExceptionHandlerTest() {
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("detail");
    }

    @Test
    void campaignNotFoundMapsTo404() {
        var pd = handler.handleCampaignNotFound(
                new AttestationCampaignNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "ATTESTATION_CAMPAIGN_NOT_FOUND");
    }

    @Test
    void itemNotFoundMapsTo404() {
        var pd = handler.handleItemNotFound(
                new AttestationItemNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "ATTESTATION_ITEM_NOT_FOUND");
    }

    @Test
    void notEligibleMapsTo403() {
        var pd = handler.handleNotEligible(
                new AttestationReviewerNotEligibleException(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "ATTESTATION_REVIEWER_NOT_ELIGIBLE");
    }

    @Test
    void invalidStateMapsTo409WithCurrentStatus() {
        var pd = handler.handleInvalidState(new IllegalAttestationCampaignTransitionException(
                AttestationCampaignStatus.OPEN, "not allowed"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties())
                .containsEntry("error", "ATTESTATION_CAMPAIGN_INVALID_STATE")
                .containsEntry("currentStatus", "OPEN");
    }

    @Test
    void invalidStateWithNullStatusOmitsProperty() {
        var pd = handler.handleInvalidState(
                new IllegalAttestationCampaignTransitionException(null, "not allowed"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).doesNotContainKey("currentStatus");
    }

    @Test
    void invalidScopeMapsTo400() {
        var pd = handler.handleInvalidScope(new IllegalAttestationScopeException("bad scope"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "ATTESTATION_INVALID_SCOPE");
    }
}
