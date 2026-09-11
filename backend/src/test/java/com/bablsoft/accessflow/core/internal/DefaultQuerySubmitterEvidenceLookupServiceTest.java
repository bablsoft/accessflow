package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QuerySubmitterEvidence;
import com.bablsoft.accessflow.core.internal.persistence.repo.QuerySubmitterEvidenceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QuerySubmitterEvidenceRepository.SubmitterEvidenceRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQuerySubmitterEvidenceLookupServiceTest {

    @Mock QuerySubmitterEvidenceRepository repository;
    @InjectMocks DefaultQuerySubmitterEvidenceLookupService service;

    @Test
    void mapsEachRowToItsUser() {
        var orgId = UUID.randomUUID();
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var lastA = Instant.parse("2026-09-10T14:02:11Z");
        var lastBreakGlassA = Instant.parse("2026-08-30T03:14:00Z");
        // Build the row mocks before stubbing the repository: a when() inside a when() argument is
        // an unfinished stubbing.
        var rows = List.of(row(userA, 143, lastA, 2, lastBreakGlassA), row(userB, 1, lastA, 0, null));
        when(repository.findBySubmitters(orgId, List.of(userA, userB))).thenReturn(rows);

        var evidence = service.findBySubmitters(orgId, List.of(userA, userB));

        assertThat(evidence).containsOnlyKeys(userA, userB);
        assertThat(evidence.get(userA)).isEqualTo(
                new QuerySubmitterEvidence(userA, 143L, lastA, 2L, lastBreakGlassA));
        assertThat(evidence.get(userB).breakGlassExecutionCount()).isZero();
        assertThat(evidence.get(userB).lastBreakGlassAt()).isNull();
    }

    @Test
    void usersWithoutSubmissionsAreAbsent() {
        var orgId = UUID.randomUUID();
        var userA = UUID.randomUUID();
        when(repository.findBySubmitters(orgId, Set.of(userA))).thenReturn(List.of());

        assertThat(service.findBySubmitters(orgId, Set.of(userA))).isEmpty();
    }

    @Test
    void emptyInputSkipsTheQuery() {
        assertThat(service.findBySubmitters(UUID.randomUUID(), List.of())).isEmpty();
        assertThat(service.findBySubmitters(UUID.randomUUID(), null)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void noneIsAllZeroes() {
        var userId = UUID.randomUUID();
        assertThat(QuerySubmitterEvidence.none(userId))
                .isEqualTo(new QuerySubmitterEvidence(userId, 0L, null, 0L, null));
    }

    private static SubmitterEvidenceRow row(UUID userId, long count, Instant last, long bgCount,
                                            Instant lastBg) {
        var row = mock(SubmitterEvidenceRow.class);
        when(row.getUserId()).thenReturn(userId);
        when(row.getSubmittedQueryCount()).thenReturn(count);
        when(row.getLastSubmittedAt()).thenReturn(last);
        when(row.getBreakGlassExecutionCount()).thenReturn(bgCount);
        when(row.getLastBreakGlassAt()).thenReturn(lastBg);
        return row;
    }
}
