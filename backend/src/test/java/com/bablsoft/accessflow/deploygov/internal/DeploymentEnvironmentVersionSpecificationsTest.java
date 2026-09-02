package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class DeploymentEnvironmentVersionSpecificationsTest {

    private Root<DeploymentEnvironmentVersionEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Order order;
    private Expression expression;
    private Subquery subquery;
    private Root environmentRoot;

    private Path organizationIdPath;
    private Path pipelineIdPath;
    private Path environmentIdPath;
    private Path updatedAtPath;
    private Path environmentTagsPath;
    private Path environmentIdSubPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        order = mock(Order.class);
        expression = mock(Expression.class);
        subquery = mock(Subquery.class);
        environmentRoot = mock(Root.class);

        organizationIdPath = mock(Path.class);
        pipelineIdPath = mock(Path.class);
        environmentIdPath = mock(Path.class);
        updatedAtPath = mock(Path.class);
        environmentTagsPath = mock(Path.class);
        environmentIdSubPath = mock(Path.class);

        when(root.get("organizationId")).thenReturn(organizationIdPath);
        when(root.get("pipelineId")).thenReturn(pipelineIdPath);
        when(root.get("environmentId")).thenReturn(environmentIdPath);
        when(root.get("updatedAt")).thenReturn(updatedAtPath);

        when(cq.subquery(UUID.class)).thenReturn(subquery);
        when(subquery.from(DeploymentEnvironmentEntity.class)).thenReturn(environmentRoot);
        when(subquery.select(any(Expression.class))).thenReturn(subquery);
        when(environmentRoot.get("tags")).thenReturn(environmentTagsPath);
        when(environmentRoot.get("id")).thenReturn(environmentIdSubPath);

        when(cb.desc(any(Expression.class))).thenReturn(order);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.equal(any(Expression.class), any(Expression.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
        when(cb.function(anyString(), any(Class.class), any(Expression[].class)))
                .thenReturn(expression);
        when(cb.gt(any(Expression.class), any(Number.class))).thenReturn(predicate);
        when(cb.literal(any())).thenReturn(expression);
        when(cb.exists(any(Subquery.class))).thenReturn(predicate);
    }

    @Test
    void noFiltersAddsOnlyTheOrganizationPredicate() {
        var org = UUID.randomUUID();

        DeploymentEnvironmentVersionSpecifications.forList(org, null, null)
                .toPredicate(root, cq, cb);

        verify(cq).orderBy(order);
        verify(cb).equal(organizationIdPath, org);
        verify(cb, never()).equal(eq(pipelineIdPath), any(Object.class));
        verify(cb, never()).exists(any(Subquery.class));
    }

    @Test
    void pipelineFilterAddsThePipelinePredicate() {
        var pipelineId = UUID.randomUUID();

        DeploymentEnvironmentVersionSpecifications.forList(UUID.randomUUID(), pipelineId, null)
                .toPredicate(root, cq, cb);

        verify(cb).equal(pipelineIdPath, pipelineId);
    }

    @Test
    void tagFilterAddsAnArrayPositionExistsSubquery() {
        DeploymentEnvironmentVersionSpecifications.forList(UUID.randomUUID(), null, " acme ")
                .toPredicate(root, cq, cb);

        // The tag is trimmed and matched via array_position(...) > 0 on the correlated
        // environment row.
        verify(cb).literal("acme");
        verify(cb).function(eq("array_position"), eq(Integer.class), any(Expression[].class));
        verify(cb).gt(expression, 0);
        verify(cb).equal(environmentIdSubPath, environmentIdPath);
        verify(cb).exists(subquery);
    }

    @Test
    void blankTagFilterIsIgnored() {
        DeploymentEnvironmentVersionSpecifications.forList(UUID.randomUUID(), null, "   ")
                .toPredicate(root, cq, cb);

        verify(cb, never()).exists(any(Subquery.class));
        verify(cb, never()).gt(any(Expression.class), any(Number.class));
    }
}
