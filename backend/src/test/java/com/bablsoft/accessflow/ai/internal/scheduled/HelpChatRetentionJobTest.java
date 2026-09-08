package com.bablsoft.accessflow.ai.internal.scheduled;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatSessionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpChatRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-09-08T03:00:00Z");

    @Mock HelpAgentConfigRepository configRepository;
    @Mock HelpChatSessionRepository sessionRepository;

    private HelpChatRetentionJob job() {
        return new HelpChatRetentionJob(configRepository, sessionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HelpAgentConfigEntity config(int retentionDays) {
        return config(retentionDays, true);
    }

    private static HelpAgentConfigEntity config(int retentionDays, boolean enabled) {
        var config = new HelpAgentConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(UUID.randomUUID());
        config.setEnabled(enabled);
        config.setRetentionDays(retentionDays);
        return config;
    }

    /**
     * The cutoff is the whole behaviour: everything last touched before it goes, everything after it
     * stays. A fixed clock is what makes "90 days ago" an exact instant to assert on.
     */
    @Test
    void deletesConversationsPastEachOrganizationsRetentionWindow() {
        var ninetyDays = config(90);
        var sevenDays = config(7);
        when(configRepository.findAll()).thenReturn(List.of(ninetyDays, sevenDays));

        job().run();

        verify(sessionRepository).deleteByOrganizationIdAndLastActivityBefore(
                ninetyDays.getOrganizationId(), Instant.parse("2026-06-10T03:00:00Z"));
        verify(sessionRepository).deleteByOrganizationIdAndLastActivityBefore(
                sevenDays.getOrganizationId(), Instant.parse("2026-09-01T03:00:00Z"));
    }

    @Test
    void doesNothingWhenNoOrganizationHasConfiguredTheAgent() {
        when(configRepository.findAll()).thenReturn(List.of());

        job().run();

        verifyNoInteractions(sessionRepository);
    }

    /**
     * Retention is a promise about data already written. Disabling the agent is the likeliest
     * reaction to a privacy concern, and it must not be the one action that makes stored transcripts
     * immortal — nothing else prunes them.
     */
    @Test
    void expiresADisabledOrganizationsTranscriptsToo() {
        var disabled = config(30, false);
        when(configRepository.findAll()).thenReturn(List.of(disabled));

        job().run();

        verify(sessionRepository).deleteByOrganizationIdAndLastActivityBefore(
                eq(disabled.getOrganizationId()), any());
    }

    /**
     * A non-positive window can only arrive by direct database edit, and honouring it would delete
     * every conversation the organization has.
     */
    @Test
    void skipsAnOrganizationWithANonPositiveRetentionWindow() {
        var broken = config(0);
        var healthy = config(30);
        when(configRepository.findAll()).thenReturn(List.of(broken, healthy));

        job().run();

        verify(sessionRepository, never()).deleteByOrganizationIdAndLastActivityBefore(
                eq(broken.getOrganizationId()), any());
        verify(sessionRepository).deleteByOrganizationIdAndLastActivityBefore(
                eq(healthy.getOrganizationId()), any());
    }

    @Test
    void oneFailingOrganizationDoesNotAbortTheBatch() {
        var failing = config(30);
        var healthy = config(30);
        when(configRepository.findAll()).thenReturn(List.of(failing, healthy));
        when(sessionRepository.deleteByOrganizationIdAndLastActivityBefore(
                eq(failing.getOrganizationId()), any()))
                .thenThrow(new IllegalStateException("connection reset"));

        assertThatCode(() -> job().run()).doesNotThrowAnyException();

        verify(sessionRepository).deleteByOrganizationIdAndLastActivityBefore(
                eq(healthy.getOrganizationId()), any());
    }

    /**
     * Without the lock, every replica runs this job on every tick and deletes the same rows N times
     * over. There is no global guard test in this suite, so the assertion lives here.
     */
    @Test
    void isClusteredSafeAndConfigurable() throws Exception {
        var run = HelpChatRetentionJob.class.getMethod("run");

        var lock = run.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("helpChatRetentionJob");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT30M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT5M");

        var scheduled = run.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${accessflow.help-agent.retention-poll-interval:PT6H}");
    }
}
