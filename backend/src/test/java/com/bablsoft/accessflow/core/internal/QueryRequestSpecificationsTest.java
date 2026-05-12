package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
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
class QueryRequestSpecificationsTest {

    private Root<QueryRequestEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Order order;

    private Path createdAtPath;
    private Path orgIdPath;
    private Path submittedByIdPath;
    private Path datasourceIdPath;
    private Path statusPath;
    private Path queryTypePath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        order = mock(Order.class);

        createdAtPath = mock(Path.class);
        orgIdPath = mock(Path.class);
        submittedByIdPath = mock(Path.class);
        datasourceIdPath = mock(Path.class);
        statusPath = mock(Path.class);
        queryTypePath = mock(Path.class);

        var organizationPath = mock(Path.class);
        var datasourcePath = mock(Path.class);
        var submittedByPath = mock(Path.class);
        when(root.get("createdAt")).thenReturn(createdAtPath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("queryType")).thenReturn(queryTypePath);
        when(root.get("datasource")).thenReturn(datasourcePath);
        when(root.get("submittedBy")).thenReturn(submittedByPath);
        when(datasourcePath.get("organization")).thenReturn(organizationPath);
        when(organizationPath.get("id")).thenReturn(orgIdPath);
        when(datasourcePath.get("id")).thenReturn(datasourceIdPath);
        when(submittedByPath.get("id")).thenReturn(submittedByIdPath);

        when(cb.desc(any(Expression.class))).thenReturn(order);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    @Test
    void emptyFilterAddsOnlyOrgPredicateAndOrdersByCreatedAtDesc() {
        var orgId = UUID.randomUUID();
        QueryRequestSpecifications.forFilter(filter(orgId, null, null, null, null, null, null))
                .toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).desc(createdAtPath);
        verify(cb).equal(orgIdPath, orgId);
        verify(cb, never()).equal(eq(submittedByIdPath), any(Object.class));
        verify(cb, never()).equal(eq(datasourceIdPath), any(Object.class));
        verify(cb, never()).equal(eq(statusPath), any(Object.class));
        verify(cb, never()).equal(eq(queryTypePath), any(Object.class));
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void allFiltersAreApplied() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");

        QueryRequestSpecifications.forFilter(
                filter(orgId, userId, dsId, QueryStatus.PENDING_REVIEW, QueryType.SELECT, from, to))
                .toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, orgId);
        verify(cb).equal(submittedByIdPath, userId);
        verify(cb).equal(datasourceIdPath, dsId);
        verify(cb).equal(statusPath, QueryStatus.PENDING_REVIEW);
        verify(cb).equal(queryTypePath, QueryType.SELECT);
        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb).lessThan(createdAtPath, to);
    }

    @Test
    void onlySubmitterFilterAddsSubmitterPredicate() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        QueryRequestSpecifications.forFilter(filter(orgId, userId, null, null, null, null, null))
                .toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, orgId);
        verify(cb).equal(submittedByIdPath, userId);
        verify(cb, never()).equal(eq(datasourceIdPath), any(Object.class));
    }

    @Test
    void onlyFromBoundAddsLowerInclusivePredicateOnly() {
        var orgId = UUID.randomUUID();
        var from = Instant.parse("2026-03-01T00:00:00Z");

        QueryRequestSpecifications.forFilter(filter(orgId, null, null, null, null, from, null))
                .toPredicate(root, cq, cb);

        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void onlyToBoundAddsUpperExclusivePredicateOnly() {
        var orgId = UUID.randomUUID();
        var to = Instant.parse("2026-03-31T00:00:00Z");

        QueryRequestSpecifications.forFilter(filter(orgId, null, null, null, null, null, to))
                .toPredicate(root, cq, cb);

        verify(cb).lessThan(createdAtPath, to);
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
    }

    private static QueryListFilter filter(UUID orgId, UUID userId, UUID dsId,
                                           QueryStatus status, QueryType queryType,
                                           Instant from, Instant to) {
        return new QueryListFilter(orgId, userId, dsId, status, queryType, from, to);
    }
}
