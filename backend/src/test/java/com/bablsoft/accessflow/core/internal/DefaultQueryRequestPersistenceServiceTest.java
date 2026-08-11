package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryRequestPersistenceServiceTest {

    @Mock QueryRequestRepository queryRequestRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @InjectMocks DefaultQueryRequestPersistenceService service;

    @Test
    void submitInsertsQueryRequestWithDefaults() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        var user = new UserEntity();
        user.setId(userId);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(queryRequestRepository.save(any(QueryRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var futureInstant = java.time.Instant.now().plusSeconds(600);
        var command = new SubmitQueryCommand(datasourceId, userId, "SELECT 1",
                QueryType.SELECT, false, "ticket-42", futureInstant, SubmissionReason.AI_SUGGESTION,
                "203.0.113.7", "curl/8.4.0", true);

        var id = service.submit(command);

        ArgumentCaptor<QueryRequestEntity> captor = ArgumentCaptor.forClass(QueryRequestEntity.class);
        verify(queryRequestRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getDatasource()).isSameAs(datasource);
        assertThat(saved.getSubmittedBy()).isSameAs(user);
        assertThat(saved.getSqlText()).isEqualTo("SELECT 1");
        assertThat(saved.getQueryType()).isEqualTo(QueryType.SELECT);
        assertThat(saved.isTransactional()).isFalse();
        assertThat(saved.getJustification()).isEqualTo("ticket-42");
        assertThat(saved.getScheduledFor()).isEqualTo(futureInstant);
        assertThat(saved.getSubmissionReason()).isEqualTo(SubmissionReason.AI_SUGGESTION);
        assertThat(saved.getSubmittedIp()).isEqualTo("203.0.113.7");
        assertThat(saved.getSubmittedUserAgent()).isEqualTo("curl/8.4.0");
        assertThat(saved.isCiCdOrigin()).isTrue();
        assertThat(saved.getStatus())
                .isEqualTo(com.bablsoft.accessflow.core.api.QueryStatus.PENDING_AI);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void submitThrowsWhenDatasourceMissing() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        var command = new SubmitQueryCommand(datasourceId, userId, "SELECT 1",
                QueryType.SELECT, false, null, null, SubmissionReason.USER_SUBMITTED,
                null, null, false);

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(datasourceId.toString());
    }

    @Test
    void submitThrowsWhenUserMissing() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        var command = new SubmitQueryCommand(datasourceId, userId, "SELECT 1",
                QueryType.SELECT, false, null, null, SubmissionReason.USER_SUBMITTED,
                null, null, false);

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(userId.toString());
    }

    // ── recurring series (#627) ───────────────────────────────────────────────

    private QueryRequestEntity recurringParent() {
        var datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        var parent = new QueryRequestEntity();
        parent.setId(UUID.randomUUID());
        parent.setDatasource(datasource);
        parent.setSubmittedBy(user);
        parent.setSqlText("SELECT * FROM public.orders");
        parent.setQueryType(QueryType.SELECT);
        parent.setTransactional(false);
        parent.setJustification("weekly report");
        parent.setStatus(com.bablsoft.accessflow.core.api.QueryStatus.APPROVED);
        parent.setRecurrenceRule("PT6H");
        parent.setRecurrenceUntil(java.time.Instant.now().plusSeconds(86400));
        parent.setRecurrenceNextRunAt(java.time.Instant.now().minusSeconds(10));
        return parent;
    }

    @Test
    void createRecurringOccurrenceCopiesParentFieldsAndAdvancesCursorAtomically() {
        var parent = recurringParent();
        var nextRunAt = java.time.Instant.now().plusSeconds(6 * 3600);
        when(queryRequestRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(queryRequestRepository.save(any(QueryRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var childId = service.createRecurringOccurrence(parent.getId(), nextRunAt);

        assertThat(childId).isPresent();
        ArgumentCaptor<QueryRequestEntity> captor = ArgumentCaptor.forClass(QueryRequestEntity.class);
        verify(queryRequestRepository).save(captor.capture());
        var child = captor.getValue();
        assertThat(child.getId()).isEqualTo(childId.get());
        assertThat(child.getDatasource()).isSameAs(parent.getDatasource());
        assertThat(child.getSubmittedBy()).isSameAs(parent.getSubmittedBy());
        assertThat(child.getSqlText()).isEqualTo(parent.getSqlText());
        assertThat(child.getQueryType()).isEqualTo(QueryType.SELECT);
        assertThat(child.getJustification()).isEqualTo("weekly report");
        // Created directly in APPROVED — an insert, not a transition; no submission event.
        assertThat(child.getStatus())
                .isEqualTo(com.bablsoft.accessflow.core.api.QueryStatus.APPROVED);
        assertThat(child.getSubmissionReason()).isEqualTo(SubmissionReason.RECURRING);
        assertThat(child.getRecurringParentId()).isEqualTo(parent.getId());
        // The child never inherits the series definition — only the parent carries it.
        assertThat(child.getRecurrenceRule()).isNull();
        assertThat(child.getRecurrenceNextRunAt()).isNull();
        // Advance-before-execute: cursor stamped in the same transaction as the insert.
        assertThat(parent.getRecurrenceNextRunAt()).isEqualTo(nextRunAt);
    }

    @Test
    void createRecurringOccurrenceReturnsEmptyWhenParentNoLongerActive() {
        var parent = recurringParent();
        parent.setStatus(com.bablsoft.accessflow.core.api.QueryStatus.CANCELLED);
        when(queryRequestRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));

        var childId = service.createRecurringOccurrence(parent.getId(), null);

        assertThat(childId).isEmpty();
        verify(queryRequestRepository, org.mockito.Mockito.never())
                .save(any(QueryRequestEntity.class));
    }

    @Test
    void createRecurringOccurrenceReturnsEmptyWhenCursorAlreadyCleared() {
        var parent = recurringParent();
        parent.setRecurrenceNextRunAt(null);
        when(queryRequestRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));

        assertThat(service.createRecurringOccurrence(parent.getId(), null)).isEmpty();
    }

    @Test
    void clearRecurrenceNextRunClearsCursorAndRecordsHaltReason() {
        var parent = recurringParent();
        when(queryRequestRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));

        service.clearRecurrenceNextRun(parent.getId(), "permission revoked");

        assertThat(parent.getRecurrenceNextRunAt()).isNull();
        assertThat(parent.getRecurrenceHaltedReason()).isEqualTo("permission revoked");
    }

    @Test
    void clearRecurrenceNextRunWithNullReasonMarksCleanCompletion() {
        var parent = recurringParent();
        when(queryRequestRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));

        service.clearRecurrenceNextRun(parent.getId(), null);

        assertThat(parent.getRecurrenceNextRunAt()).isNull();
        assertThat(parent.getRecurrenceHaltedReason()).isNull();
    }
}
