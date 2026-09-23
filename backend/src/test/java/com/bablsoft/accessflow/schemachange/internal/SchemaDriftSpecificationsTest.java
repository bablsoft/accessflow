package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
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
class SchemaDriftSpecificationsTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private Root root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Path orgIdPath;
    private Path pipelineIdPath;
    private Path environmentIdPath;
    private Path statusPath;
    private Path startedAtPath;
    private Path lastSeenAtPath;
    private Path idPath;
    private Join scanJoin;
    private Path joinedPipelineIdPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        orgIdPath = mock(Path.class);
        pipelineIdPath = mock(Path.class);
        environmentIdPath = mock(Path.class);
        statusPath = mock(Path.class);
        startedAtPath = mock(Path.class);
        lastSeenAtPath = mock(Path.class);
        idPath = mock(Path.class);
        scanJoin = mock(Join.class);
        joinedPipelineIdPath = mock(Path.class);

        when(root.get("organizationId")).thenReturn(orgIdPath);
        lenient().when(root.get("pipelineId")).thenReturn(pipelineIdPath);
        lenient().when(root.get("environmentId")).thenReturn(environmentIdPath);
        lenient().when(root.get("status")).thenReturn(statusPath);
        lenient().when(root.get("startedAt")).thenReturn(startedAtPath);
        lenient().when(root.get("lastSeenAt")).thenReturn(lastSeenAtPath);
        when(root.get("id")).thenReturn(idPath);
        lenient().when(root.join("scan")).thenReturn(scanJoin);
        lenient().when(scanJoin.get("pipelineId")).thenReturn(joinedPipelineIdPath);
        when(cb.desc(any(Expression.class))).thenReturn(mock(Order.class));
        when(cb.asc(any(Expression.class))).thenReturn(mock(Order.class));
        lenient().when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    // --- scans -----------------------------------------------------------------------------------

    @Test
    void scansWithoutFiltersScopeToTheOrganisationAndOrderNewestFirst() {
        var result = SchemaDriftSpecifications.scans(organizationId, SchemaDriftScanListFilter.unfiltered())
                .toPredicate(root, cq, cb);

        assertThat(result).isSameAs(predicate);
        verify(cb).equal(orgIdPath, organizationId);
        verify(cb, never()).equal(eq(pipelineIdPath), any());
        verify(cb, never()).equal(eq(environmentIdPath), any());
        verify(cb).desc(startedAtPath);
        verify(cb).asc(idPath);
    }

    @Test
    void aNullScanFilterBehavesLikeNoFilter() {
        SchemaDriftSpecifications.scans(organizationId, null).toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, organizationId);
        verify(cb, never()).equal(eq(pipelineIdPath), any());
    }

    @Test
    void theScanPipelineFilterReadsTheScansOwnColumn() {
        SchemaDriftSpecifications.scans(organizationId, new SchemaDriftScanListFilter(pipelineId, null))
                .toPredicate(root, cq, cb);

        verify(cb).equal(pipelineIdPath, pipelineId);
        verify(root, never()).join("scan");
    }

    @Test
    void theScanEnvironmentFilterAddsItsPredicate() {
        SchemaDriftSpecifications.scans(organizationId, new SchemaDriftScanListFilter(null, environmentId))
                .toPredicate(root, cq, cb);

        verify(cb).equal(environmentIdPath, environmentId);
    }

    // --- findings --------------------------------------------------------------------------------

    @Test
    void findingsWithoutFiltersScopeToTheOrganisationAndOrderByLastSeen() {
        var result = SchemaDriftSpecifications
                .findings(organizationId, SchemaDriftFindingListFilter.unfiltered())
                .toPredicate(root, cq, cb);

        assertThat(result).isSameAs(predicate);
        verify(cb).equal(orgIdPath, organizationId);
        verify(cb).desc(lastSeenAtPath);
        verify(cb).asc(idPath);
        verify(root, never()).join("scan");
    }

    @Test
    void aNullFindingFilterBehavesLikeNoFilter() {
        SchemaDriftSpecifications.findings(organizationId, null).toPredicate(root, cq, cb);

        verify(cb).equal(orgIdPath, organizationId);
        verify(root, never()).join("scan");
    }

    @Test
    void theFindingStatusFilterAddsItsPredicate() {
        SchemaDriftSpecifications.findings(organizationId,
                        new SchemaDriftFindingListFilter(null, null, SchemaDriftFindingStatus.OPEN))
                .toPredicate(root, cq, cb);

        verify(cb).equal(statusPath, SchemaDriftFindingStatus.OPEN);
    }

    @Test
    void theFindingEnvironmentFilterReadsTheDenormalisedColumn() {
        SchemaDriftSpecifications.findings(organizationId,
                        new SchemaDriftFindingListFilter(null, environmentId, null))
                .toPredicate(root, cq, cb);

        verify(cb).equal(environmentIdPath, environmentId);
        verify(root, never()).join("scan");
    }

    @Test
    void theFindingPipelineFilterJoinsTheOwningScan() {
        SchemaDriftSpecifications.findings(organizationId,
                        new SchemaDriftFindingListFilter(pipelineId, null, null))
                .toPredicate(root, cq, cb);

        // Joined rather than resolved through deploygov: findings outlive the environments they name,
        // and the join keeps them visible after one is deleted.
        verify(root).join("scan");
        verify(cb).equal(joinedPipelineIdPath, pipelineId);
    }
}
