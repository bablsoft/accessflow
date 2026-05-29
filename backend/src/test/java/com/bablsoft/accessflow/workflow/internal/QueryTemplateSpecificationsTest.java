package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateFilter;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class QueryTemplateSpecificationsTest {

    private Root<QueryTemplateEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Order order;
    private Expression expression;

    private Path organizationIdPath;
    private Path ownerIdPath;
    private Path visibilityPath;
    private Path datasourceIdPath;
    private Path tagsPath;
    private Path namePath;
    private Path descriptionPath;
    private Path updatedAtPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        order = mock(Order.class);
        expression = mock(Expression.class);

        organizationIdPath = mock(Path.class);
        ownerIdPath = mock(Path.class);
        visibilityPath = mock(Path.class);
        datasourceIdPath = mock(Path.class);
        tagsPath = mock(Path.class);
        namePath = mock(Path.class);
        descriptionPath = mock(Path.class);
        updatedAtPath = mock(Path.class);

        when(root.get("organizationId")).thenReturn(organizationIdPath);
        when(root.get("ownerId")).thenReturn(ownerIdPath);
        when(root.get("visibility")).thenReturn(visibilityPath);
        when(root.get("datasourceId")).thenReturn(datasourceIdPath);
        when(root.get("tags")).thenReturn(tagsPath);
        when(root.get("name")).thenReturn(namePath);
        when(root.get("description")).thenReturn(descriptionPath);
        when(root.get("updatedAt")).thenReturn(updatedAtPath);

        when(cb.desc(any(Expression.class))).thenReturn(order);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
        when(cb.like(any(Expression.class), anyString())).thenReturn(predicate);
        when(cb.lower(any(Expression.class))).thenReturn(expression);
        when(cb.function(anyString(), any(Class.class), any(Expression[].class))).thenReturn(expression);
        when(cb.isNotNull(any(Expression.class))).thenReturn(predicate);
        when(cb.literal(any())).thenReturn(expression);
    }

    @Test
    void noExtraFiltersAddsOnlyOrgAndVisibilityPredicates() {
        var org = UUID.randomUUID();
        var caller = UUID.randomUUID();

        QueryTemplateSpecifications.forList(org, caller, null).toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).equal(organizationIdPath, org);
        verify(cb).equal(ownerIdPath, caller);
        verify(cb).equal(visibilityPath, QueryTemplateVisibility.TEAM);
        verify(cb, never()).equal(eq(datasourceIdPath), any(Object.class));
        verify(cb, never()).like(any(Expression.class), anyString());
        verify(cb, never()).isNotNull(any(Expression.class));
    }

    @Test
    void datasourceFilterIsApplied() {
        var ds = UUID.randomUUID();
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(),
                new QueryTemplateFilter(ds, null, null, null)).toPredicate(root, cq, cb);

        verify(cb).equal(datasourceIdPath, ds);
    }

    @Test
    void visibilityFilterIsApplied() {
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(),
                new QueryTemplateFilter(null, null, QueryTemplateVisibility.PRIVATE, null))
                .toPredicate(root, cq, cb);

        verify(cb).equal(visibilityPath, QueryTemplateVisibility.PRIVATE);
    }

    @Test
    void tagFilterAddsArrayPositionPredicate() {
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(),
                new QueryTemplateFilter(null, "billing", null, null))
                .toPredicate(root, cq, cb);

        verify(cb, atLeastOnce()).function(eq("array_position"), eq(Integer.class), any(Expression[].class));
        verify(cb).isNotNull(any(Expression.class));
    }

    @Test
    void blankTagFilterIsIgnored() {
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(),
                new QueryTemplateFilter(null, "   ", null, null))
                .toPredicate(root, cq, cb);

        verify(cb, never()).isNotNull(any(Expression.class));
    }

    @Test
    void searchFilterMatchesNameAndDescription() {
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(),
                new QueryTemplateFilter(null, null, null, "TopUsers"))
                .toPredicate(root, cq, cb);

        // Both name and description go through cb.like("%topusers%", ...).
        verify(cb, atLeastOnce()).like(any(Expression.class), eq("%topusers%"));
        verify(cb, atLeastOnce()).lower(any(Expression.class));
    }

    @Test
    void blankSearchFilterIsIgnored() {
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(),
                new QueryTemplateFilter(null, null, null, "   "))
                .toPredicate(root, cq, cb);

        verify(cb, never()).like(any(Expression.class), anyString());
    }

    @Test
    void resultsAreOrderedByUpdatedAtDesc() {
        QueryTemplateSpecifications.forList(UUID.randomUUID(), UUID.randomUUID(), null)
                .toPredicate(root, cq, cb);

        verify(cb).desc(updatedAtPath);
        verify(cq).orderBy(order);
    }
}
