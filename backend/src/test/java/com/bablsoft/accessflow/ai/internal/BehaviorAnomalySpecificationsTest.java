package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AnomalyListFilter;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
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
class BehaviorAnomalySpecificationsTest {

    private Root<BehaviorAnomalyEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Path organizationIdPath;
    private Path userIdPath;
    private Path datasourceIdPath;
    private Path featurePath;
    private Path statusPath;
    private Path detectedAtPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);

        organizationIdPath = mock(Path.class);
        userIdPath = mock(Path.class);
        datasourceIdPath = mock(Path.class);
        featurePath = mock(Path.class);
        statusPath = mock(Path.class);
        detectedAtPath = mock(Path.class);

        when(root.get("organizationId")).thenReturn(organizationIdPath);
        when(root.get("userId")).thenReturn(userIdPath);
        when(root.get("datasourceId")).thenReturn(datasourceIdPath);
        when(root.get("feature")).thenReturn(featurePath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("detectedAt")).thenReturn(detectedAtPath);

        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    @Test
    void emptyFilterAddsOnlyOrgPredicate() {
        var organizationId = UUID.randomUUID();
        BehaviorAnomalySpecifications.forQuery(organizationId, AnomalyListFilter.empty())
                .toPredicate(root, cq, cb);

        verify(cb).equal(organizationIdPath, organizationId);
        verify(cb, never()).equal(eq(userIdPath), any(Object.class));
        verify(cb, never()).equal(eq(datasourceIdPath), any(Object.class));
        verify(cb, never()).equal(eq(featurePath), any(Object.class));
        verify(cb, never()).equal(eq(statusPath), any(Object.class));
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void allFiltersAreApplied() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2026-02-01T00:00:00Z");
        var filter = new AnomalyListFilter(userId, datasourceId, "query_count",
                BehaviorAnomalyStatus.OPEN, from, to);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).equal(organizationIdPath, organizationId);
        verify(cb).equal(userIdPath, userId);
        verify(cb).equal(datasourceIdPath, datasourceId);
        verify(cb).equal(featurePath, "query_count");
        verify(cb).equal(statusPath, BehaviorAnomalyStatus.OPEN);
        verify(cb).greaterThanOrEqualTo(detectedAtPath, from);
        verify(cb).lessThan(detectedAtPath, to);
    }

    @Test
    void onlyUserFilterAddsUserPredicate() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var filter = new AnomalyListFilter(userId, null, null, null, null, null);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).equal(userIdPath, userId);
        verify(cb, never()).equal(eq(datasourceIdPath), any(Object.class));
    }

    @Test
    void onlyDatasourceFilterAddsDatasourcePredicate() {
        var organizationId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var filter = new AnomalyListFilter(null, datasourceId, null, null, null, null);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).equal(datasourceIdPath, datasourceId);
        verify(cb, never()).equal(eq(userIdPath), any(Object.class));
    }

    @Test
    void blankFeatureFilterIsIgnored() {
        var organizationId = UUID.randomUUID();
        var filter = new AnomalyListFilter(null, null, "   ", null, null, null);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb, never()).equal(eq(featurePath), any(Object.class));
    }

    @Test
    void onlyFeatureFilterAddsFeaturePredicate() {
        var organizationId = UUID.randomUUID();
        var filter = new AnomalyListFilter(null, null, "active_hours", null, null, null);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).equal(featurePath, "active_hours");
    }

    @Test
    void onlyStatusFilterAddsStatusPredicate() {
        var organizationId = UUID.randomUUID();
        var filter = new AnomalyListFilter(null, null, null, BehaviorAnomalyStatus.DISMISSED, null, null);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).equal(statusPath, BehaviorAnomalyStatus.DISMISSED);
    }

    @Test
    void onlyFromFilterAddsLowerBound() {
        var organizationId = UUID.randomUUID();
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var filter = new AnomalyListFilter(null, null, null, null, from, null);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).greaterThanOrEqualTo(detectedAtPath, from);
        verify(cb, never()).lessThan(any(Expression.class), any(Instant.class));
    }

    @Test
    void onlyToFilterAddsUpperBound() {
        var organizationId = UUID.randomUUID();
        var to = Instant.parse("2026-02-01T00:00:00Z");
        var filter = new AnomalyListFilter(null, null, null, null, null, to);

        BehaviorAnomalySpecifications.forQuery(organizationId, filter).toPredicate(root, cq, cb);

        verify(cb).lessThan(detectedAtPath, to);
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Instant.class));
    }
}
