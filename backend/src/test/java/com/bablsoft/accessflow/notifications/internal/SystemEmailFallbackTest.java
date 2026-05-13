package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.codec.EmailChannelConfig;
import com.bablsoft.accessflow.notifications.internal.strategy.EmailNotificationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemEmailFallbackTest {

    @Mock SystemSmtpService systemSmtpService;
    @Mock EmailNotificationStrategy emailStrategy;

    private SystemEmailFallback fallback;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fallback = new SystemEmailFallback(systemSmtpService, emailStrategy);
    }

    @Test
    void skipsWhenContextIsNull() {
        fallback.deliverIfPossible(null);

        verify(systemSmtpService, never()).resolveSendingConfig(orgId);
        verify(emailStrategy, never()).deliverInternal(any(), any());
    }

    @Test
    void skipsWhenSystemSmtpNotConfigured() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.empty());

        fallback.deliverIfPossible(sampleCtx());

        verify(emailStrategy, never()).deliverInternal(any(), any());
    }

    @Test
    void deliversThroughEmailStrategyWhenConfigured() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "smtp.example.com", 587, "user", "secret",
                        true, "from@example.com", "From")));

        fallback.deliverIfPossible(sampleCtx());

        var captor = ArgumentCaptor.forClass(EmailChannelConfig.class);
        verify(emailStrategy).deliverInternal(any(), captor.capture());
        var cfg = captor.getValue();
        assertThat(cfg.smtpHost()).isEqualTo("smtp.example.com");
        assertThat(cfg.smtpPasswordPlain()).isEqualTo("secret");
        assertThat(cfg.fromAddress()).isEqualTo("from@example.com");
    }

    @Test
    void runtimeFailureIsSwallowed() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "smtp.example.com", 587, null, null,
                        true, "from@example.com", null)));
        doThrow(new RuntimeException("boom")).when(emailStrategy).deliverInternal(any(), any());

        fallback.deliverIfPossible(sampleCtx());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    private NotificationContext sampleCtx() {
        return new NotificationContext(
                NotificationEventType.QUERY_APPROVED,
                orgId, UUID.randomUUID(), QueryType.SELECT,
                "SELECT 1", "SELECT 1", "SELECT 1",
                RiskLevel.LOW, 10, "ok",
                UUID.randomUUID(), "ds",
                UUID.randomUUID(), "submit@example.com", "Sub",
                null, null, null, null,
                URI.create("https://app.example.test/queries/x"),
                List.of(new RecipientView(UUID.randomUUID(), "a@example.com", "A")),
                Instant.now());
    }
}
