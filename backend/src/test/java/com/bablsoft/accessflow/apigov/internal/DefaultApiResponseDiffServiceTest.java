package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffCommand;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffIncompatibleException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffNotAvailableException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffUnsupportedException;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultApiResponseDiffServiceTest {

    private static final Set<Permission> VIEW_ALL = Set.of(Permission.QUERY_VIEW_ALL);

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-03T10:00:00Z");

    private ApiRequestRepository repository;
    private DefaultApiResponseDiffService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApiRequestRepository.class);
        service = new DefaultApiResponseDiffService(repository,
                new ApigovRequestProperties(1L, 1L, 1L, 1, 500, 0, null), JsonMapper.builder().build());
    }

    private ApiRequestEntity run(String operationId, String snapshot, Instant createdAt) {
        var e = new ApiRequestEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setSubmittedBy(userId);
        e.setOperationId(operationId);
        e.setVerb("GET");
        e.setRequestPath("/v1/orders");
        e.setStatus(QueryStatus.EXECUTED);
        e.setResponseSnapshot(snapshot);
        e.setResponseContentType("application/json");
        e.setResponseStatusCode(200);
        e.setResponseDurationMs(100);
        e.setResponseBytes(10L);
        e.setCreatedAt(createdAt);
        return e;
    }

    private void stored(ApiRequestEntity e) {
        when(repository.findByIdAndOrganizationId(e.getId(), orgId)).thenReturn(Optional.of(e));
    }

    private ApiResponseDiffCommand command(UUID id, UUID against, Set<Permission> permissions) {
        return new ApiResponseDiffCommand(id, against, orgId, userId, permissions);
    }

    @Test
    void previousResolvedByOperationIdComputesEntriesAndDeltas() {
        var current = run("listOrders", "{\"total\":3,\"next\":\"x\"}", now);
        current.setResponseDurationMs(80);
        current.setResponseBytes(14L);
        current.setResponseStatusCode(201);
        var previous = run("listOrders", "{\"total\":2,\"gone\":1}", now.minus(1, ChronoUnit.HOURS));
        previous.setResponseTruncated(true);
        stored(current);
        when(repository.findPreviousRunByOperation(orgId, connectorId, "listOrders", now, current.getId(), null))
                .thenReturn(Optional.of(previous));

        var view = service.diff(command(current.getId(), null, VIEW_ALL));

        assertThat(view.currentId()).isEqualTo(current.getId());
        assertThat(view.previousId()).isEqualTo(previous.getId());
        assertThat(view.format()).isEqualTo(ApiResponseDiffFormat.JSON);
        assertThat(view.statusCodeCurrent()).isEqualTo(201);
        assertThat(view.statusCodePrevious()).isEqualTo(200);
        assertThat(view.durationMsDelta()).isEqualTo(-20L);
        assertThat(view.bytesDelta()).isEqualTo(4L);
        assertThat(view.entries()).containsExactly(
                new ApiResponseDiffEntry("total", ApiResponseDiffKind.CHANGED, "2", "3"),
                new ApiResponseDiffEntry("gone", ApiResponseDiffKind.REMOVED, "1", null),
                new ApiResponseDiffEntry("next", ApiResponseDiffKind.ADDED, null, "\"x\""));
        assertThat(view.addedCount()).isEqualTo(1);
        assertThat(view.removedCount()).isEqualTo(1);
        assertThat(view.changedCount()).isEqualTo(1);
        assertThat(view.truncated()).isFalse();
        assertThat(view.eitherResponseTruncated()).isTrue();
    }

    @Test
    void previousResolvedByVerbAndPathForAFreeFormCall() {
        var current = run(null, "{}", now);
        var previous = run(null, "{}", now.minusSeconds(5));
        stored(current);
        when(repository.findPreviousRunByVerbAndPath(orgId, connectorId, "GET", "/v1/orders", now,
                current.getId(), null)).thenReturn(Optional.of(previous));

        var view = service.diff(command(current.getId(), null, VIEW_ALL));

        assertThat(view.previousId()).isEqualTo(previous.getId());
        assertThat(view.entries()).isEmpty();
        assertThat(view.eitherResponseTruncated()).isFalse();
    }

    @Test
    void previousIsScopedToTheCallerWithoutViewAll() {
        var current = run("listOrders", "{}", now);
        stored(current);
        when(repository.findPreviousRunByOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.diff(command(current.getId(), null, Set.of())))
                .isInstanceOf(ApiResponseDiffNotAvailableException.class);
        verify(repository).findPreviousRunByOperation(orgId, connectorId, "listOrders", now, current.getId(),
                userId);
    }

    @Test
    void noPreviousRunIsNotAvailable() {
        var current = run("listOrders", "{}", now);
        stored(current);
        when(repository.findPreviousRunByOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.diff(command(current.getId(), null, VIEW_ALL)))
                .isInstanceOf(ApiResponseDiffNotAvailableException.class)
                .satisfies(ex -> assertThat(((ApiResponseDiffNotAvailableException) ex).apiRequestId())
                        .isEqualTo(current.getId()));
    }

    @Test
    void currentWithoutSnapshotIsNotAvailable() {
        var current = run("listOrders", null, now);
        stored(current);

        assertThatThrownBy(() -> service.diff(command(current.getId(), null, VIEW_ALL)))
                .isInstanceOf(ApiResponseDiffNotAvailableException.class);
        verify(repository, never()).findPreviousRunByOperation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownCurrentIsNotFound() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.diff(command(id, null, VIEW_ALL)))
                .isInstanceOf(ApiRequestNotFoundException.class);
    }

    @Test
    void currentNotVisibleIsNotFound() {
        var current = run("listOrders", "{}", now);
        current.setSubmittedBy(UUID.randomUUID());
        stored(current);

        assertThatThrownBy(() -> service.diff(command(current.getId(), null, Set.of())))
                .isInstanceOf(ApiRequestNotFoundException.class);
    }

    @Test
    void explicitOtherSideNotVisibleIsNotFoundNeverForbidden() {
        var current = run("listOrders", "{}", now);
        var other = run("listOrders", "{}", now.minusSeconds(60));
        other.setSubmittedBy(UUID.randomUUID());
        stored(current);
        stored(other);

        assertThatThrownBy(() -> service.diff(command(current.getId(), other.getId(), Set.of())))
                .isInstanceOf(ApiRequestNotFoundException.class);
    }

    @Test
    void explicitOtherSideIsDiffed() {
        var current = run("listOrders", "{\"a\":2}", now);
        var other = run("listOrders", "{\"a\":1}", now.minusSeconds(60));
        stored(current);
        stored(other);

        var view = service.diff(command(current.getId(), other.getId(), Set.of()));

        assertThat(view.previousId()).isEqualTo(other.getId());
        assertThat(view.changedCount()).isEqualTo(1);
    }

    @Test
    void explicitFreeFormOtherSideIsDiffed() {
        var current = run(null, "{}", now);
        var other = run(null, "{}", now.minusSeconds(60));
        stored(current);
        stored(other);

        assertThat(service.diff(command(current.getId(), other.getId(), VIEW_ALL)).previousId())
                .isEqualTo(other.getId());
    }

    @Test
    void explicitOtherSideOnAnotherConnectorIsIncompatible() {
        var current = run("listOrders", "{}", now);
        var other = run("listOrders", "{}", now.minusSeconds(60));
        other.setConnectorId(UUID.randomUUID());
        assertIncompatible(current, other);
    }

    @Test
    void explicitOtherSideOnAnotherOperationIsIncompatible() {
        var current = run("listOrders", "{}", now);
        assertIncompatible(current, run("getOrder", "{}", now.minusSeconds(60)));
        assertIncompatible(current, run(null, "{}", now.minusSeconds(60)));
    }

    @Test
    void explicitFreeFormOtherSideWithAnotherPathOrVerbIsIncompatible() {
        var current = run(null, "{}", now);
        var otherPath = run(null, "{}", now.minusSeconds(60));
        otherPath.setRequestPath("/v1/customers");
        assertIncompatible(current, otherPath);
        var otherVerb = run(null, "{}", now.minusSeconds(60));
        otherVerb.setVerb("POST");
        assertIncompatible(current, otherVerb);
        assertIncompatible(current, run("listOrders", "{}", now.minusSeconds(60)));
    }

    @Test
    void explicitOtherSideThatIsNotAnEarlierExecutedRunWithASnapshotIsIncompatible() {
        var current = run("listOrders", "{}", now);
        var failed = run("listOrders", "{}", now.minusSeconds(60));
        failed.setStatus(QueryStatus.FAILED);
        assertIncompatible(current, failed);
        assertIncompatible(current, run("listOrders", null, now.minusSeconds(60)));
        assertIncompatible(current, run("listOrders", "{}", now.plusSeconds(60)));
        stored(current);
        assertThatThrownBy(() -> service.diff(command(current.getId(), current.getId(), VIEW_ALL)))
                .isInstanceOf(ApiResponseDiffIncompatibleException.class);
    }

    private void assertIncompatible(ApiRequestEntity current, ApiRequestEntity other) {
        stored(current);
        stored(other);
        assertThatThrownBy(() -> service.diff(command(current.getId(), other.getId(), VIEW_ALL)))
                .isInstanceOf(ApiResponseDiffIncompatibleException.class);
    }

    @Test
    void csvIsUnsupported() {
        var current = run("listOrders", "a,b\n1,2", now);
        current.setResponseContentType("text/csv");
        stored(current);
        when(repository.findPreviousRunByOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(run("listOrders", "a,b\n1,3", now.minusSeconds(60))));

        assertThatThrownBy(() -> service.diff(command(current.getId(), null, VIEW_ALL)))
                .isInstanceOf(ApiResponseDiffUnsupportedException.class)
                .satisfies(ex -> assertThat(((ApiResponseDiffUnsupportedException) ex).contentType())
                        .isEqualTo("text/csv"));
    }

    @Test
    void xmlContentTypeIsDiffedAsXml() {
        var current = run("listOrders", "<r><a>2</a></r>", now);
        current.setResponseContentType("application/soap+xml; charset=utf-8");
        stored(current);
        when(repository.findPreviousRunByOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(run("listOrders", "<r><a>1</a></r>", now.minusSeconds(60))));

        var view = service.diff(command(current.getId(), null, VIEW_ALL));

        assertThat(view.format()).isEqualTo(ApiResponseDiffFormat.XML);
        assertThat(view.entries()).containsExactly(
                new ApiResponseDiffEntry("r[0].a[0].#text", ApiResponseDiffKind.CHANGED, "1", "2"));
    }

    @Test
    void snapshotMaskedOnOneSideOnlyShowsAsChanged() {
        var current = run("listOrders", "{\"email\":\"***\"}", now);
        stored(current);
        when(repository.findPreviousRunByOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(run("listOrders", "{\"email\":\"a@b.c\"}", now.minusSeconds(60))));

        var view = service.diff(command(current.getId(), null, VIEW_ALL));

        assertThat(view.entries()).containsExactly(
                new ApiResponseDiffEntry("email", ApiResponseDiffKind.CHANGED, "\"a@b.c\"", "\"***\""));
    }

    @Test
    void metadataDeltasAreNullWhenASideLacksTheNumber() {
        var current = run("listOrders", "{}", now);
        current.setResponseDurationMs(null);
        var previous = run("listOrders", "{}", now.minusSeconds(60));
        previous.setResponseBytes(null);
        previous.setResponseStatusCode(null);
        stored(current);
        when(repository.findPreviousRunByOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(previous));

        var view = service.diff(command(current.getId(), null, VIEW_ALL));

        assertThat(view.durationMsDelta()).isNull();
        assertThat(view.bytesDelta()).isNull();
        assertThat(view.statusCodePrevious()).isNull();
    }

    @Test
    void formatDetectionFallsBackToTheBodyShapeOnlyWithoutAContentType() {
        assertThat(DefaultApiResponseDiffService.detectFormat(null, " {\"a\":1}"))
                .isEqualTo(ApiResponseDiffFormat.JSON);
        assertThat(DefaultApiResponseDiffService.detectFormat("", "[1]")).isEqualTo(ApiResponseDiffFormat.JSON);
        assertThat(DefaultApiResponseDiffService.detectFormat(" ", "<r/>")).isEqualTo(ApiResponseDiffFormat.XML);
        assertThat(DefaultApiResponseDiffService.detectFormat("application/problem+json", "x"))
                .isEqualTo(ApiResponseDiffFormat.JSON);
        assertThatThrownBy(() -> DefaultApiResponseDiffService.detectFormat(null, "plain text"))
                .isInstanceOf(ApiResponseDiffUnsupportedException.class);
        assertThatThrownBy(() -> DefaultApiResponseDiffService.detectFormat("text/html", "<html/>"))
                .isInstanceOf(ApiResponseDiffUnsupportedException.class);
    }
}
