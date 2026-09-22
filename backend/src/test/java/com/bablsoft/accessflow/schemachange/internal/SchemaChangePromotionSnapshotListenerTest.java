package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaChangePromotionSnapshotListenerTest {

    @Mock
    private SchemaChangeSetPromotionRepository promotionRepository;
    @Mock
    private DatasourceAdminService datasourceAdminService;

    private final UUID promotionId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-22T11:30:00Z");

    private SchemaChangePromotionSnapshotListener listener;

    @BeforeEach
    void setUp() {
        listener = new SchemaChangePromotionSnapshotListener(promotionRepository, datasourceAdminService,
                new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
        lenient().when(promotionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** It runs after the transition is committed precisely so the projection never waits on remote I/O. */
    @Test
    void runsAsynchronouslyAfterTheTransitionCommits() throws Exception {
        var method = SchemaChangePromotionSnapshotListener.class
                .getDeclaredMethod("onPromotionStatusChanged", SchemaChangePromotionStatusChangedEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        var annotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isTrue();
    }

    @Test
    void storesTheIntrospectedSchemaAndTheTimestamp() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPLIED, null);
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenReturn(new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                        List.of(new DatabaseSchemaView.Table("orders",
                                List.of(new DatabaseSchemaView.Column("id", "int4", false, true)), List.of()))))));

        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPLIED));

        assertThat(promotion.getSchemaSnapshot()).contains("\"public\"").contains("\"orders\"");
        assertThat(promotion.getSnapshotTakenAt()).isEqualTo(now);
        verify(promotionRepository).saveAndFlush(promotion);
    }

    @ParameterizedTest
    @EnumSource(value = SchemaChangePromotionStatus.class,
            names = {"PENDING", "IN_REVIEW", "APPROVED", "FAILED", "PARTIALLY_APPLIED", "CANCELLED"})
    void ignoresEveryStatusButApplied(SchemaChangePromotionStatus status) {
        listener.onPromotionStatusChanged(event(status));

        verifyNoInteractions(promotionRepository, datasourceAdminService);
    }

    @Test
    void ignoresAPromotionThatIsGone() {
        when(promotionRepository.findById(promotionId)).thenReturn(Optional.empty());

        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPLIED));

        verifyNoInteractions(datasourceAdminService);
        verify(promotionRepository, never()).saveAndFlush(any());
    }

    /** Idempotent: a duplicate delivery must not re-introspect or overwrite the baseline. */
    @Test
    void ignoresAPromotionThatAlreadyCarriesASnapshot() {
        givenPromotion(SchemaChangePromotionStatus.APPLIED, "{\"schemas\":[]}");

        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPLIED));

        verifyNoInteractions(datasourceAdminService);
        verify(promotionRepository, never()).saveAndFlush(any());
    }

    @Test
    void ignoresAPromotionThatHasSinceMovedOn() {
        givenPromotion(SchemaChangePromotionStatus.FAILED, null);

        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPLIED));

        verifyNoInteractions(datasourceAdminService);
    }

    /** Losing the baseline is recoverable; letting the failure escape the async listener is not. */
    @Test
    void swallowsAnUnreachableTarget() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPLIED, null);
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThatCode(() -> listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPLIED)))
                .doesNotThrowAnyException();
        assertThat(promotion.getSchemaSnapshot()).isNull();
        assertThat(promotion.getSnapshotTakenAt()).isNull();
    }

    private SchemaChangeSetPromotionEntity givenPromotion(SchemaChangePromotionStatus status, String snapshot) {
        var promotion = new SchemaChangeSetPromotionEntity();
        promotion.setId(promotionId);
        promotion.setOrganizationId(organizationId);
        promotion.setDatasourceId(datasourceId);
        promotion.setStatus(status);
        promotion.setSchemaSnapshot(snapshot);
        when(promotionRepository.findById(promotionId)).thenReturn(Optional.of(promotion));
        return promotion;
    }

    private SchemaChangePromotionStatusChangedEvent event(SchemaChangePromotionStatus newStatus) {
        return new SchemaChangePromotionStatusChangedEvent(promotionId, UUID.randomUUID(), UUID.randomUUID(),
                organizationId, UUID.randomUUID(), SchemaChangePromotionStatus.APPROVED, newStatus);
    }
}
