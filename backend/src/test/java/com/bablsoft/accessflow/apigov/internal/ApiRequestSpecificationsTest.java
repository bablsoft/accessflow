package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestListFilter;
import com.bablsoft.accessflow.core.api.QueryStatus;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class ApiRequestSpecificationsTest {

    private Root root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Order order;

    private Path createdAtPath;
    private Path orgIdPath;
    private Path submittedByPath;
    private Path connectorIdPath;
    private Path statusPath;
    private Path verbPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        order = mock(Order.class);

        createdAtPath = mock(Path.class);
        orgIdPath = mock(Path.class);
        submittedByPath = mock(Path.class);
        connectorIdPath = mock(Path.class);
        statusPath = mock(Path.class);
        verbPath = mock(Path.class);

        when(root.get("createdAt")).thenReturn(createdAtPath);
        when(root.get("organizationId")).thenReturn(orgIdPath);
        when(root.get("submittedBy")).thenReturn(submittedByPath);
        when(root.get("connectorId")).thenReturn(connectorIdPath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("verb")).thenReturn(verbPath);

        when(cb.desc(any(Expression.class))).thenReturn(order);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.notEqual(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    @Test
    void emptyFilterAddsOnlyOrgPredicateAndOrdersByCreatedAtDesc() {
        var orgId = UUID.randomUUID();
        ApiRequestSpecifications.forFilter(
                new ApiRequestListFilter(orgId, null, null, null, null, null, null))
                .toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).desc(createdAtPath);
        verify(cb).equal(orgIdPath, orgId);
        verify(cb, never()).equal(eq(submittedByPath), any(Object.class));
        verify(cb, never()).equal(eq(connectorIdPath), any(Object.class));
        verify(cb, never()).equal(eq(statusPath), any(Object.class));
        verify(cb, never()).equal(eq(verbPath), any(Object.class));
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void allFiltersAreApplied() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");

        ApiRequestSpecifications.forFilter(new ApiRequestListFilter(orgId, userId, connectorId,
                QueryStatus.PENDING_REVIEW, "GET", from, to)).toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, orgId);
        verify(cb).equal(submittedByPath, userId);
        verify(cb).equal(connectorIdPath, connectorId);
        verify(cb).equal(statusPath, QueryStatus.PENDING_REVIEW);
        verify(cb).equal(verbPath, "GET");
        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb).lessThan(createdAtPath, to);
    }

    @Test
    void blankVerbIsIgnored() {
        var orgId = UUID.randomUUID();
        ApiRequestSpecifications.forFilter(
                new ApiRequestListFilter(orgId, null, null, null, "  ", null, null))
                .toPredicate(root, cq, cb);

        verify(cb, never()).equal(eq(verbPath), any(Object.class));
    }

    @Test
    void onlyFromBoundAddsLowerInclusivePredicateOnly() {
        var orgId = UUID.randomUUID();
        var from = Instant.parse("2026-03-01T00:00:00Z");
        ApiRequestSpecifications.forFilter(
                new ApiRequestListFilter(orgId, null, null, null, null, from, null))
                .toPredicate(root, cq, cb);

        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void pendingReviewExcludesReviewerAndPinsStatus() {
        var orgId = UUID.randomUUID();
        var reviewerId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();

        ApiRequestSpecifications.forPendingReview(orgId, reviewerId, connectorId, "POST")
                .toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).equal(orgIdPath, orgId);
        verify(cb).equal(statusPath, QueryStatus.PENDING_REVIEW);
        verify(cb).notEqual(submittedByPath, reviewerId);
        verify(cb).equal(connectorIdPath, connectorId);
        verify(cb).equal(verbPath, "POST");
    }

    @Test
    void pendingReviewWithoutNarrowingOmitsConnectorAndVerb() {
        var orgId = UUID.randomUUID();
        var reviewerId = UUID.randomUUID();

        ApiRequestSpecifications.forPendingReview(orgId, reviewerId, null, null)
                .toPredicate(root, cq, cb);

        verify(cb).notEqual(submittedByPath, reviewerId);
        verify(cb, never()).equal(eq(connectorIdPath), any(Object.class));
        verify(cb, never()).equal(eq(verbPath), any(Object.class));
    }
}
