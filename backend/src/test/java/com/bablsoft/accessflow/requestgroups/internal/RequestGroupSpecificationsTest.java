package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class RequestGroupSpecificationsTest {

    private Root root;
    private CriteriaQuery cq;
    private CriteriaBuilder cb;
    private Predicate predicate;
    private Order order;
    private Path createdAtPath;
    private Path orgIdPath;
    private Path statusPath;
    private Path submittedByPath;
    private Path onBehalfOfPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        cq = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        predicate = mock(Predicate.class);
        order = mock(Order.class);
        createdAtPath = mock(Path.class);
        orgIdPath = mock(Path.class);
        statusPath = mock(Path.class);
        submittedByPath = mock(Path.class);
        onBehalfOfPath = mock(Path.class);
        when(root.get("createdAt")).thenReturn(createdAtPath);
        when(root.get("organizationId")).thenReturn(orgIdPath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("submittedBy")).thenReturn(submittedByPath);
        when(root.get("onBehalfOfUserId")).thenReturn(onBehalfOfPath);
        when(cb.desc(any(Expression.class))).thenReturn(order);
        lenient().when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        lenient().when(cb.notEqual(any(Expression.class), any(Object.class))).thenReturn(predicate);
        lenient().when(cb.isNull(any(Expression.class))).thenReturn(predicate);
        lenient().when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        lenient().when(cb.and(any(Predicate[].class))).thenReturn(predicate);
    }

    /** #874: the queue hides what the decision would refuse — for both submitter identities. */
    @Test
    void pendingReviewExcludesBothSubmitterIdentitiesAndOrdersByCreatedAtDesc() {
        var orgId = UUID.randomUUID();
        var reviewerId = UUID.randomUUID();

        var result = RequestGroupSpecifications.forPendingReview(orgId, reviewerId).toPredicate(root, cq, cb);

        assertThat(result).isSameAs(predicate);
        verify(cq).orderBy(order);
        verify(cb).desc(createdAtPath);
        verify(cb).equal(orgIdPath, orgId);
        verify(cb).equal(statusPath, RequestGroupStatus.PENDING_REVIEW);
        verify(cb).notEqual(submittedByPath, reviewerId);
        verify(cb).isNull(onBehalfOfPath);
        verify(cb).notEqual(onBehalfOfPath, reviewerId);
    }
}
