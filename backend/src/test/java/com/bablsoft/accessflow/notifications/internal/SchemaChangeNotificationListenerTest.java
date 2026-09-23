package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.events.SchemaDriftDetectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SchemaChangeNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    private SchemaChangeNotificationListener listener;

    private final UUID promotionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new SchemaChangeNotificationListener(dispatcher);
    }

    /** Reviewers are pinged when the group reaches review, not when the promotion is submitted. */
    @Test
    void enteringReviewNotifiesSubmitted() {
        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.PENDING,
                SchemaChangePromotionStatus.IN_REVIEW));
        verify(dispatcher).dispatchSchemaChangePromotion(
                NotificationEventType.SCHEMA_CHANGE_PROMOTION_SUBMITTED, promotionId);
    }

    @Test
    void appliedNotifiesThePromoter() {
        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPROVED,
                SchemaChangePromotionStatus.APPLIED));
        verify(dispatcher).dispatchSchemaChangePromotion(
                NotificationEventType.SCHEMA_CHANGE_PROMOTION_APPLIED, promotionId);
    }

    @ParameterizedTest
    @EnumSource(value = SchemaChangePromotionStatus.class, names = {"FAILED", "PARTIALLY_APPLIED"})
    void aFailedOrPartialRunNotifiesFailed(SchemaChangePromotionStatus status) {
        listener.onPromotionStatusChanged(event(SchemaChangePromotionStatus.APPROVED, status));
        verify(dispatcher).dispatchSchemaChangePromotion(
                NotificationEventType.SCHEMA_CHANGE_PROMOTION_FAILED, promotionId);
    }

    @ParameterizedTest
    @EnumSource(value = SchemaChangePromotionStatus.class, names = {"PENDING", "APPROVED", "CANCELLED"})
    void otherTransitionsNotifyNobody(SchemaChangePromotionStatus status) {
        listener.onPromotionStatusChanged(event(null, status));
        verify(dispatcher, never()).dispatchSchemaChangePromotion(any(), any());
    }

    @Test
    void aNullStatusMapsToNothing() {
        org.assertj.core.api.Assertions.assertThat(SchemaChangeNotificationListener.mapStatus(null)).isNull();
        verifyNoInteractions(dispatcher);
    }

    @Test
    void driftDetectionIsHandedToTheDispatcher() {
        var event = new SchemaDriftDetectedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2);
        listener.onDriftDetected(event);
        verify(dispatcher).dispatchSchemaDrift(event);
    }

    private SchemaChangePromotionStatusChangedEvent event(SchemaChangePromotionStatus from,
                                                          SchemaChangePromotionStatus to) {
        return new SchemaChangePromotionStatusChangedEvent(promotionId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), from, to);
    }
}
