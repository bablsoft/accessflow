package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.CreateDataClassificationTagCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.IllegalDataClassificationTagException;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryFindingStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Mock
    private DiscoveryFindingRepository findingRepository;
    @Mock
    private DataClassificationAdminService dataClassificationAdminService;

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private DiscoveryFindingStateService service() {
        return new DiscoveryFindingStateService(findingRepository,
                dataClassificationAdminService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DiscoveryFindingEntity finding(String schemaName) {
        var finding = new DiscoveryFindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setOrganizationId(orgId);
        finding.setDatasourceId(dsId);
        finding.setSchemaName(schemaName);
        finding.setTableName("users");
        finding.setColumnName("email");
        finding.setClassification(DataClassification.PII);
        finding.setDetector(DiscoveryDetector.EMAIL);
        finding.setConfidence(96);
        finding.setStatus(DiscoveryFindingStatus.PENDING);
        return finding;
    }

    @Test
    void confirmAppliesTagWithSchemaQualifiedTableAndMarksConfirmed() {
        var finding = finding("public");

        var outcome = service().confirm(finding, actorId);

        var captor = ArgumentCaptor.forClass(CreateDataClassificationTagCommand.class);
        verify(dataClassificationAdminService).create(eq(dsId), eq(orgId), captor.capture());
        var command = captor.getValue();
        assertThat(command.tableName()).isEqualTo("public.users");
        assertThat(command.columnName()).isEqualTo("email");
        assertThat(command.classifications()).containsExactly(DataClassification.PII);
        assertThat(command.applyMasking()).isTrue();
        assertThat(command.note()).contains("EMAIL").contains("96%");

        assertThat(outcome.tagConflict()).isFalse();
        assertThat(finding.getStatus()).isEqualTo(DiscoveryFindingStatus.CONFIRMED);
        assertThat(finding.getDecidedBy()).isEqualTo(actorId);
        assertThat(finding.getDecidedAt()).isEqualTo(NOW);
        verify(findingRepository).save(finding);
    }

    @Test
    void confirmUsesBareTableNameWithoutSchema() {
        var finding = finding(null);

        service().confirm(finding, actorId);

        var captor = ArgumentCaptor.forClass(CreateDataClassificationTagCommand.class);
        verify(dataClassificationAdminService).create(eq(dsId), eq(orgId), captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("users");
    }

    @Test
    void existingTagStillConfirmsButReportsConflict() {
        var finding = finding("public");
        when(dataClassificationAdminService.create(any(), any(), any()))
                .thenThrow(new IllegalDataClassificationTagException("duplicate"));

        var outcome = service().confirm(finding, actorId);

        assertThat(outcome.tagConflict()).isTrue();
        assertThat(finding.getStatus()).isEqualTo(DiscoveryFindingStatus.CONFIRMED);
        verify(findingRepository).save(finding);
    }

    @Test
    void dismissMarksDismissedWithoutTouchingTags() {
        var finding = finding("public");

        var dismissed = service().dismiss(finding, actorId);

        assertThat(dismissed.getStatus()).isEqualTo(DiscoveryFindingStatus.DISMISSED);
        assertThat(dismissed.getDecidedBy()).isEqualTo(actorId);
        assertThat(dismissed.getDecidedAt()).isEqualTo(NOW);
        org.mockito.Mockito.verifyNoInteractions(dataClassificationAdminService);
        verify(findingRepository).save(finding);
    }
}
