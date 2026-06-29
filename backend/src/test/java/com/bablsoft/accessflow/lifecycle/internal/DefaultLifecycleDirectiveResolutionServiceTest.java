package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLifecycleDirectiveResolutionServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();

    @Mock
    private RetentionPolicyRepository policyRepository;
    @Mock
    private LifecycleSaltService saltService;
    @InjectMocks
    private DefaultLifecycleDirectiveResolutionService service;

    private RetentionPolicyEntity policy(LifecycleAction action, LifecycleTransform transform,
                                         String table, String... columns) {
        var p = new RetentionPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setDatasourceId(DS);
        p.setAction(action);
        p.setTransformType(transform);
        p.setTargetTable(table);
        p.setTargetColumns(columns);
        return p;
    }

    @Test
    void emptyWhenNoPseudonymizePolicies() {
        when(policyRepository.findAllByDatasourceIdAndEnabledTrue(DS))
                .thenReturn(List.of(policy(LifecycleAction.HARD_DELETE, null, "orders", "email")));

        assertThat(service.resolveColumnMasks(ORG, DS)).isEmpty();
        verify(saltService, never()).currentSalt(ORG);
    }

    @Test
    void saltedHashDirectivePerColumn() {
        when(policyRepository.findAllByDatasourceIdAndEnabledTrue(DS)).thenReturn(List.of(
                policy(LifecycleAction.PSEUDONYMIZE, LifecycleTransform.SHA256_SALTED, "users",
                        "email", "ssn")));
        when(saltService.currentSalt(ORG)).thenReturn("S");

        var masks = service.resolveColumnMasks(ORG, DS);

        assertThat(masks).hasSize(2);
        assertThat(masks).allSatisfy(m -> {
            assertThat(m.strategy()).isEqualTo(MaskingStrategy.HASH);
            assertThat(m.params()).containsEntry("salt", "S");
        });
        assertThat(masks).extracting(m -> m.columnRef())
                .containsExactlyInAnyOrder("users.email", "users.ssn");
    }

    @Test
    void formatPreservingNeedsNoSalt() {
        when(policyRepository.findAllByDatasourceIdAndEnabledTrue(DS)).thenReturn(List.of(
                policy(LifecycleAction.PSEUDONYMIZE, LifecycleTransform.FORMAT_PRESERVING, null,
                        "phone")));

        var masks = service.resolveColumnMasks(ORG, DS);

        assertThat(masks).singleElement().satisfies(m -> {
            assertThat(m.strategy()).isEqualTo(MaskingStrategy.FORMAT_PRESERVING);
            assertThat(m.columnRef()).isEqualTo("phone");
            assertThat(m.params()).isEmpty();
        });
        verify(saltService, never()).currentSalt(ORG);
    }

    @Test
    void tokenizationUsesPrefixedSalt() {
        when(policyRepository.findAllByDatasourceIdAndEnabledTrue(DS)).thenReturn(List.of(
                policy(LifecycleAction.PSEUDONYMIZE, LifecycleTransform.TOKENIZATION, "t", "c")));
        when(saltService.currentSalt(ORG)).thenReturn("S");

        assertThat(service.resolveColumnMasks(ORG, DS)).singleElement()
                .satisfies(m -> assertThat(m.params()).containsEntry("salt", "tok:S"));
    }

    @Test
    void skipsPseudonymizeWithNoColumns() {
        var p = policy(LifecycleAction.PSEUDONYMIZE, LifecycleTransform.SHA256_SALTED, "t");
        p.setTargetColumns(new String[0]);
        lenient().when(saltService.currentSalt(ORG)).thenReturn("S");
        when(policyRepository.findAllByDatasourceIdAndEnabledTrue(DS)).thenReturn(List.of(p));

        assertThat(service.resolveColumnMasks(ORG, DS)).isEmpty();
    }
}
