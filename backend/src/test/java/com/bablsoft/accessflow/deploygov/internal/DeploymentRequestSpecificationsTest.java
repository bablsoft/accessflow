package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class DeploymentRequestSpecificationsTest {

    private Root root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Predicate disjunction;
    private Order order;

    private Path createdAtPath;
    private Path orgIdPath;
    private Path submittedByPath;
    private Path pipelineIdPath;
    private Path environmentIdPath;
    private Path versionPath;
    private Path statusPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        disjunction = mock(Predicate.class);
        order = mock(Order.class);

        createdAtPath = mock(Path.class);
        orgIdPath = mock(Path.class);
        submittedByPath = mock(Path.class);
        pipelineIdPath = mock(Path.class);
        environmentIdPath = mock(Path.class);
        versionPath = mock(Path.class);
        statusPath = mock(Path.class);

        when(root.get("createdAt")).thenReturn(createdAtPath);
        lenient().when(root.get("organizationId")).thenReturn(orgIdPath);
        lenient().when(root.get("submittedBy")).thenReturn(submittedByPath);
        lenient().when(root.get("pipelineId")).thenReturn(pipelineIdPath);
        lenient().when(root.get("environmentId")).thenReturn(environmentIdPath);
        lenient().when(root.get("version")).thenReturn(versionPath);
        lenient().when(root.get("status")).thenReturn(statusPath);

        when(cb.desc(any(Expression.class))).thenReturn(order);
        lenient().when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        lenient().when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class)))
                .thenReturn(predicate);
        lenient().when(cb.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        lenient().when(cb.and(any(Predicate[].class))).thenReturn(predicate);
        lenient().when(cb.disjunction()).thenReturn(disjunction);
        lenient().when(environmentIdPath.in(any(java.util.Collection.class))).thenReturn(predicate);
    }

    @Test
    void emptyFilterAddsOnlyTheOrgPredicateAndOrdersByCreatedAtDesc() {
        var orgId = UUID.randomUUID();

        DeploymentRequestSpecifications.forFilter(
                new DeploymentRequestListFilter(orgId, null, null, null, null, null, null, null),
                List.of()).toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).desc(createdAtPath);
        verify(cb).equal(orgIdPath, orgId);
        verify(cb, never()).equal(eq(submittedByPath), any(Object.class));
        verify(cb, never()).equal(eq(pipelineIdPath), any(Object.class));
        verify(cb, never()).equal(eq(versionPath), any(Object.class));
        verify(cb, never()).equal(eq(statusPath), any(Object.class));
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
        verify(cb, never()).disjunction();
    }

    @Test
    void everyFilterIsApplied() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");

        DeploymentRequestSpecifications.forFilter(
                new DeploymentRequestListFilter(orgId, userId, pipelineId, "production", " 2.4.1 ",
                        QueryStatus.PENDING_REVIEW, from, to),
                List.of(environmentId)).toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, orgId);
        verify(cb).equal(submittedByPath, userId);
        verify(cb).equal(pipelineIdPath, pipelineId);
        verify(environmentIdPath).in(List.of(environmentId));
        verify(cb).equal(versionPath, "2.4.1");
        verify(cb).equal(statusPath, QueryStatus.PENDING_REVIEW);
        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb).lessThan(createdAtPath, to);
    }

    @Test
    void anEnvironmentNameThatResolvesToNothingPagesEmptyRatherThanUnfiltered() {
        var orgId = UUID.randomUUID();

        var result = DeploymentRequestSpecifications.forFilter(
                new DeploymentRequestListFilter(orgId, null, null, "does-not-exist", null, null,
                        null, null), List.of()).toPredicate(root, cq, cb);

        assertThat(result).isSameAs(disjunction);
        verify(cb, never()).equal(eq(orgIdPath), any(Object.class));
    }

    @Test
    void blankEnvironmentAndVersionAreIgnored() {
        var orgId = UUID.randomUUID();

        DeploymentRequestSpecifications.forFilter(
                new DeploymentRequestListFilter(orgId, null, null, "  ", "  ", null, null, null),
                List.of()).toPredicate(root, cq, cb);

        verify(cb, never()).disjunction();
        verify(environmentIdPath, never()).in(any(java.util.Collection.class));
        verify(cb, never()).equal(eq(versionPath), any(Object.class));
    }

    @Test
    void onlyTheFromBoundAddsTheLowerInclusivePredicate() {
        var orgId = UUID.randomUUID();
        var from = Instant.parse("2026-03-01T00:00:00Z");

        DeploymentRequestSpecifications.forFilter(
                new DeploymentRequestListFilter(orgId, null, null, null, null, null, from, null),
                List.of()).toPredicate(root, cq, cb);

        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }
}
