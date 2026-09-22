package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class SchemaChangeSetSpecificationsTest {

    private final UUID organizationId = UUID.randomUUID();
    private Root root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Path createdAtPath;
    private Path idPath;
    private Path orgIdPath;
    private Path pipelineIdPath;
    private Path statusPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        createdAtPath = mock(Path.class);
        idPath = mock(Path.class);
        orgIdPath = mock(Path.class);
        pipelineIdPath = mock(Path.class);
        statusPath = mock(Path.class);

        when(root.get("createdAt")).thenReturn(createdAtPath);
        when(root.get("id")).thenReturn(idPath);
        when(root.get("organizationId")).thenReturn(orgIdPath);
        lenient().when(root.get("pipelineId")).thenReturn(pipelineIdPath);
        lenient().when(root.get("status")).thenReturn(statusPath);
        when(cb.desc(any(Expression.class))).thenReturn(mock(Order.class));
        when(cb.asc(any(Expression.class))).thenReturn(mock(Order.class));
        lenient().when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    @Test
    void noFilterScopesToTheOrganizationOnlyAndOrdersNewestFirst() {
        var result = SchemaChangeSetSpecifications.forFilter(organizationId, SchemaChangeSetListFilter.none())
                .toPredicate(root, cq, cb);

        assertThat(result).isSameAs(predicate);
        verify(cb).equal(orgIdPath, organizationId);
        verify(cb, never()).equal(eq(pipelineIdPath), any());
        verify(cb, never()).equal(eq(statusPath), any());
        verify(cb).desc(createdAtPath);
        verify(cb).asc(idPath);
        verify(cq).orderBy(any(Order.class), any(Order.class));
    }

    @Test
    void nullFilterBehavesLikeNoFilter() {
        SchemaChangeSetSpecifications.forFilter(organizationId, null).toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, organizationId);
        verify(cb, never()).equal(eq(pipelineIdPath), any());
        verify(cb, never()).equal(eq(statusPath), any());
    }

    @Test
    void pipelineFilterAddsThePipelinePredicate() {
        var pipelineId = UUID.randomUUID();

        SchemaChangeSetSpecifications.forFilter(organizationId, new SchemaChangeSetListFilter(pipelineId, null))
                .toPredicate(root, cq, cb);

        verify(cb).equal(pipelineIdPath, pipelineId);
        verify(cb, never()).equal(eq(statusPath), any());
    }

    @Test
    void statusFilterAddsTheStatusPredicate() {
        SchemaChangeSetSpecifications.forFilter(organizationId,
                        new SchemaChangeSetListFilter(null, SchemaChangeSetStatus.ARCHIVED))
                .toPredicate(root, cq, cb);

        verify(cb).equal(statusPath, SchemaChangeSetStatus.ARCHIVED);
        verify(cb, never()).equal(eq(pipelineIdPath), any());
    }

    @Test
    void bothFiltersCombine() {
        var pipelineId = UUID.randomUUID();

        SchemaChangeSetSpecifications.forFilter(organizationId,
                        new SchemaChangeSetListFilter(pipelineId, SchemaChangeSetStatus.DRAFT))
                .toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, organizationId);
        verify(cb).equal(pipelineIdPath, pipelineId);
        verify(cb).equal(statusPath, SchemaChangeSetStatus.DRAFT);
    }
}
