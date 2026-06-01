package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class AccessRequestSpecificationsTest {

    private Root<AccessGrantRequestEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Path requesterPath;
    private Path organizationPath;
    private Path statusPath;

    private final UUID requesterId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        requesterPath = mock(Path.class);
        organizationPath = mock(Path.class);
        statusPath = mock(Path.class);
        when(root.get("requesterId")).thenReturn(requesterPath);
        when(root.get("organizationId")).thenReturn(organizationPath);
        when(root.get("status")).thenReturn(statusPath);
        when(cb.equal(any(), any())).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    @Test
    void mineWithoutStatusFilterOmitsStatusPredicate() {
        var spec = AccessRequestSpecifications.mine(requesterId, organizationId, null);
        var result = spec.toPredicate(root, cq, cb);
        assertThat(result).isSameAs(predicate);
        verify(cb).equal(requesterPath, requesterId);
        verify(cb).equal(organizationPath, organizationId);
        verify(root, never()).get("status");
    }

    @Test
    void mineWithStatusFilterAddsStatusPredicate() {
        var spec = AccessRequestSpecifications.mine(requesterId, organizationId,
                AccessGrantStatus.PENDING);
        spec.toPredicate(root, cq, cb);
        verify(cb).equal(statusPath, AccessGrantStatus.PENDING);
    }
}
