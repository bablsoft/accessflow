package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.lifecycle.api.CreateRetentionPolicyCommand;
import com.bablsoft.accessflow.lifecycle.api.InvalidRetentionPolicyException;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecyclePreviewResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyView;
import com.bablsoft.accessflow.lifecycle.api.UpdateRetentionPolicyCommand;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRetentionPolicyServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID POLICY = UUID.randomUUID();

    @Mock
    private RetentionPolicyRepository repository;
    @Mock
    private RetentionPolicyViewMapper mapper;
    @Mock
    private LifecyclePreviewCalculator previewCalculator;
    @Mock
    private ErasureConditionValidator conditionValidator;
    @Mock
    private ErasureConditionCodec conditionCodec;

    private DefaultRetentionPolicyService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        service = new DefaultRetentionPolicyService(repository, mapper, previewCalculator,
                conditionValidator, conditionCodec, clock);
    }

    private CreateRetentionPolicyCommand create(LifecycleAction action, LifecycleTransform transform,
                                                String table, String window) {
        return new CreateRetentionPolicyCommand(ORG, DS, "Retention", "desc", table,
                List.of("email"), null, "created_at", window, action, transform, null, null, null,
                null, true, USER);
    }

    @Test
    void create_persistsAndReturnsView() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toView(any())).thenReturn(stubView());

        var view = service.create(create(LifecycleAction.HARD_DELETE, null, "orders", "P30D"));

        assertThat(view).isNotNull();
        verify(repository).save(any(RetentionPolicyEntity.class));
    }

    @Test
    void create_rejectsWhenNoTarget() {
        assertThatThrownBy(() -> service.create(create(LifecycleAction.HARD_DELETE, null, null, "P30D")))
                .isInstanceOf(InvalidRetentionPolicyException.class)
                .extracting(e -> ((InvalidRetentionPolicyException) e).reason())
                .isEqualTo(InvalidRetentionPolicyException.Reason.NO_TARGET);
        verify(repository, never()).save(any());
    }

    @Test
    void create_rejectsPseudonymizeWithoutTransform() {
        assertThatThrownBy(() -> service.create(create(LifecycleAction.PSEUDONYMIZE, null, "orders", "P30D")))
                .isInstanceOf(InvalidRetentionPolicyException.class)
                .extracting(e -> ((InvalidRetentionPolicyException) e).reason())
                .isEqualTo(InvalidRetentionPolicyException.Reason.TRANSFORM_REQUIRED);
    }

    @Test
    void create_rejectsTransformOnNonPseudonymize() {
        assertThatThrownBy(() -> service.create(
                create(LifecycleAction.HARD_DELETE, LifecycleTransform.SHA256_SALTED, "orders", "P30D")))
                .isInstanceOf(InvalidRetentionPolicyException.class)
                .extracting(e -> ((InvalidRetentionPolicyException) e).reason())
                .isEqualTo(InvalidRetentionPolicyException.Reason.TRANSFORM_NOT_ALLOWED);
    }

    @Test
    void create_rejectsInvalidWindow() {
        assertThatThrownBy(() -> service.create(create(LifecycleAction.HARD_DELETE, null, "orders", "30 days")))
                .isInstanceOf(InvalidRetentionPolicyException.class)
                .extracting(e -> ((InvalidRetentionPolicyException) e).reason())
                .isEqualTo(InvalidRetentionPolicyException.Reason.INVALID_WINDOW);
    }

    @Test
    void update_throwsWhenMissing() {
        when(repository.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.empty());
        var cmd = new UpdateRetentionPolicyCommand(POLICY, ORG, "Retention", null, "orders",
                List.of(), null, "created_at", "P30D", LifecycleAction.HARD_DELETE, null, null,
                null, null, null, true);
        assertThatThrownBy(() -> service.update(cmd))
                .isInstanceOf(RetentionPolicyNotFoundException.class);
    }

    @Test
    void update_appliesAndSaves() {
        var entity = new RetentionPolicyEntity();
        entity.setId(POLICY);
        when(repository.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toView(entity)).thenReturn(stubView());
        var cmd = new UpdateRetentionPolicyCommand(POLICY, ORG, "Retention", null, "orders",
                List.of(), null, "created_at", "P7Y", LifecycleAction.SOFT_DELETE, null, "deleted_at",
                null, null, null, false);

        var view = service.update(cmd);

        assertThat(view).isNotNull();
        assertThat(entity.getAction()).isEqualTo(LifecycleAction.SOFT_DELETE);
        assertThat(entity.isEnabled()).isFalse();
    }

    @Test
    void get_throwsWhenMissing() {
        when(repository.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(POLICY, ORG))
                .isInstanceOf(RetentionPolicyNotFoundException.class);
    }

    @Test
    void list_mapsPage() {
        var entity = new RetentionPolicyEntity();
        Page<RetentionPolicyEntity> page = new PageImpl<>(List.of(entity));
        when(repository.findAllByOrganizationId(any(), any())).thenReturn(page);
        when(mapper.toView(entity)).thenReturn(stubView());

        var result = service.list(ORG, PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
    }

    @Test
    void delete_throwsWhenMissing() {
        when(repository.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(POLICY, ORG))
                .isInstanceOf(RetentionPolicyNotFoundException.class);
    }

    @Test
    void delete_removesEntity() {
        var entity = new RetentionPolicyEntity();
        when(repository.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.of(entity));
        service.delete(POLICY, ORG);
        verify(repository).delete(entity);
    }

    @Test
    void preview_delegatesToCalculator() {
        var entity = new RetentionPolicyEntity();
        when(repository.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.of(entity));
        var expected = new LifecyclePreviewResult(LifecycleAction.HARD_DELETE, null, 0L, List.of());
        when(previewCalculator.preview(entity)).thenReturn(expected);

        assertThat(service.preview(POLICY, ORG)).isSameAs(expected);
    }

    private static RetentionPolicyView stubView() {
        return new RetentionPolicyView(POLICY, ORG, DS, "DS", "Retention", null, "orders",
                List.of("email"), null, "created_at", "P30D", LifecycleAction.HARD_DELETE, null,
                null, null, null, null, null, null, true, USER, Instant.now(), Instant.now());
    }
}
