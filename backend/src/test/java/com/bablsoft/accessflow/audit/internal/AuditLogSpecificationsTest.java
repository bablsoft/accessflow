package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class AuditLogSpecificationsTest {

    private Root<AuditLogEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Order order;
    private Path createdAtPath;
    private Path organizationIdPath;
    private Path actorIdPath;
    private Path actionPath;
    private Path resourceTypePath;
    private Path resourceIdPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        order = mock(Order.class);

        createdAtPath = mock(Path.class);
        organizationIdPath = mock(Path.class);
        actorIdPath = mock(Path.class);
        actionPath = mock(Path.class);
        resourceTypePath = mock(Path.class);
        resourceIdPath = mock(Path.class);

        when(root.get("createdAt")).thenReturn(createdAtPath);
        when(root.get("organizationId")).thenReturn(organizationIdPath);
        when(root.get("actorId")).thenReturn(actorIdPath);
        when(root.get("action")).thenReturn(actionPath);
        when(root.get("resourceType")).thenReturn(resourceTypePath);
        when(root.get("resourceId")).thenReturn(resourceIdPath);

        when(cb.desc(any(Expression.class))).thenReturn(order);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    @Test
    void emptyFilterAddsOnlyOrgPredicate() {
        var organizationId = UUID.randomUUID();
        AuditLogSpecifications.forQuery(organizationId, AuditLogQuery.empty())
                .toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).equal(organizationIdPath, organizationId);
        verify(cb, never()).equal(eq(actorIdPath), any(Object.class));
        verify(cb, never()).equal(eq(actionPath), any(Object.class));
        verify(cb, never()).equal(eq(resourceTypePath), any(Object.class));
        verify(cb, never()).equal(eq(resourceIdPath), any(Object.class));
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void allFiltersAreApplied() {
        var organizationId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var resourceId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");
        var query = new AuditLogQuery(actorId, AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST, resourceId, from, to);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).equal(organizationIdPath, organizationId);
        verify(cb).equal(actorIdPath, actorId);
        verify(cb).equal(actionPath, "QUERY_SUBMITTED");
        verify(cb).equal(resourceTypePath, "query_request");
        verify(cb).equal(resourceIdPath, resourceId);
        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb).lessThan(createdAtPath, to);
    }

    @Test
    void onlyActorFilterAddsActorPredicate() {
        var organizationId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var query = new AuditLogQuery(actorId, null, null, null, null, null);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).equal(organizationIdPath, organizationId);
        verify(cb).equal(actorIdPath, actorId);
        verify(cb, never()).equal(eq(actionPath), any(Object.class));
        verify(cb, never()).equal(eq(resourceTypePath), any(Object.class));
        verify(cb, never()).equal(eq(resourceIdPath), any(Object.class));
    }

    @Test
    void onlyActionFilterAddsActionPredicate() {
        var organizationId = UUID.randomUUID();
        var query = new AuditLogQuery(null, AuditAction.USER_LOGIN, null, null, null, null);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).equal(actionPath, "USER_LOGIN");
        verify(cb, never()).equal(eq(actorIdPath), any(Object.class));
    }

    @Test
    void onlyResourceTypeFilterAddsResourceTypePredicate() {
        var organizationId = UUID.randomUUID();
        var query = new AuditLogQuery(null, null, AuditResourceType.DATASOURCE, null, null, null);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).equal(resourceTypePath, "datasource");
        verify(cb, never()).equal(eq(resourceIdPath), any(Object.class));
    }

    @Test
    void onlyResourceIdFilterAddsResourceIdPredicate() {
        var organizationId = UUID.randomUUID();
        var resourceId = UUID.randomUUID();
        var query = new AuditLogQuery(null, null, null, resourceId, null, null);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).equal(resourceIdPath, resourceId);
        verify(cb, never()).equal(eq(resourceTypePath), any(Object.class));
    }

    @Test
    void onlyFromFilterAddsLowerBound() {
        var organizationId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var query = new AuditLogQuery(null, null, null, null, from, null);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).greaterThanOrEqualTo(createdAtPath, from);
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void onlyToFilterAddsUpperBound() {
        var organizationId = UUID.randomUUID();
        var to = Instant.parse("2026-02-01T00:00:00Z");
        var query = new AuditLogQuery(null, null, null, null, null, to);

        AuditLogSpecifications.forQuery(organizationId, query).toPredicate(root, cq, cb);

        verify(cb).lessThan(createdAtPath, to);
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
    }

    @Test
    void resultsAreOrderedByCreatedAtDesc() {
        AuditLogSpecifications.forQuery(UUID.randomUUID(), AuditLogQuery.empty())
                .toPredicate(root, cq, cb);

        verify(cb).desc(createdAtPath);
        verify(cq, times(1)).orderBy(order);
    }
}
