package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorBaselineEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorBaselineRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBehaviorBaselineServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();

    private final BehaviorBaselineRepository repo = mock(BehaviorBaselineRepository.class);
    private final tools.jackson.databind.ObjectMapper objectMapper =
            new tools.jackson.databind.ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DefaultBehaviorBaselineService service =
            new DefaultBehaviorBaselineService(repo, objectMapper, clock);

    private static WindowFeatures window(int queryCount) {
        var hist = new int[24];
        hist[9] = queryCount;
        return new WindowFeatures(Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"), queryCount, hist, 1, Set.of("orders"),
                Map.of("SELECT", queryCount), 50.0, 0.0, Set.of(9));
    }

    @Test
    void loadReturnsTransientEmptyProfileWhenNoEntityExists() {
        when(repo.findByOrganizationIdAndUserIdAndDatasourceId(ORG, USER, DS))
                .thenReturn(Optional.empty());

        var state = service.load(ORG, USER, DS);

        assertThat(state.profile().windowsFolded()).isZero();
        assertThat(state.entity().getOrganizationId()).isEqualTo(ORG);
        assertThat(state.entity().getUserId()).isEqualTo(USER);
        assertThat(state.entity().getDatasourceId()).isEqualTo(DS);
        assertThat(state.entity().getId()).isNotNull();
        verify(repo, never()).save(any());
    }

    @Test
    void loadParsesExistingEntityFeatures() {
        var seed = BaselineProfile.empty().fold(window(5), 90).fold(window(7), 90);
        var entity = new BehaviorBaselineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setUserId(USER);
        entity.setDatasourceId(DS);
        entity.setFeatures(objectMapper.writeValueAsString(seed));
        when(repo.findByOrganizationIdAndUserIdAndDatasourceId(ORG, USER, DS))
                .thenReturn(Optional.of(entity));

        var state = service.load(ORG, USER, DS);

        assertThat(state.entity()).isSameAs(entity);
        assertThat(state.profile().windowsFolded()).isEqualTo(2);
        assertThat(state.profile().scalar(BaselineProfile.QUERY_COUNT)).containsExactly(5.0, 7.0);
    }

    @Test
    void loadDegradesToEmptyForBlankFeatures() {
        var entity = baselineEntity("");
        when(repo.findByOrganizationIdAndUserIdAndDatasourceId(ORG, USER, DS))
                .thenReturn(Optional.of(entity));

        assertThat(service.load(ORG, USER, DS).profile().windowsFolded()).isZero();
    }

    @Test
    void loadDegradesToEmptyForEmptyJsonObject() {
        var entity = baselineEntity("{}");
        when(repo.findByOrganizationIdAndUserIdAndDatasourceId(ORG, USER, DS))
                .thenReturn(Optional.of(entity));

        assertThat(service.load(ORG, USER, DS).profile().windowsFolded()).isZero();
    }

    @Test
    void loadDegradesToEmptyForGarbageJson() {
        var entity = baselineEntity("{ not valid json ]");
        when(repo.findByOrganizationIdAndUserIdAndDatasourceId(ORG, USER, DS))
                .thenReturn(Optional.of(entity));

        assertThat(service.load(ORG, USER, DS).profile().windowsFolded()).isZero();
    }

    @Test
    void loadDegradesToEmptyForNullFeatures() {
        var entity = baselineEntity(null);
        when(repo.findByOrganizationIdAndUserIdAndDatasourceId(ORG, USER, DS))
                .thenReturn(Optional.of(entity));

        assertThat(service.load(ORG, USER, DS).profile().windowsFolded()).isZero();
    }

    @Test
    void alreadyFoldedFalseWhenLastWindowStartNull() {
        var entity = new BehaviorBaselineEntity();
        var state = new BaselineState(entity, BaselineProfile.empty());
        assertThat(service.alreadyFolded(state, Instant.parse("2026-01-01T09:00:00Z"))).isFalse();
    }

    @Test
    void alreadyFoldedTrueWhenWindowEqualsLast() {
        var ws = Instant.parse("2026-01-01T09:00:00Z");
        var entity = new BehaviorBaselineEntity();
        entity.setLastWindowStart(ws);
        var state = new BaselineState(entity, BaselineProfile.empty());
        assertThat(service.alreadyFolded(state, ws)).isTrue();
    }

    @Test
    void alreadyFoldedTrueWhenWindowBeforeLast() {
        var entity = new BehaviorBaselineEntity();
        entity.setLastWindowStart(Instant.parse("2026-01-01T10:00:00Z"));
        var state = new BaselineState(entity, BaselineProfile.empty());
        assertThat(service.alreadyFolded(state, Instant.parse("2026-01-01T09:00:00Z"))).isTrue();
    }

    @Test
    void alreadyFoldedFalseWhenWindowAfterLast() {
        var entity = new BehaviorBaselineEntity();
        entity.setLastWindowStart(Instant.parse("2026-01-01T09:00:00Z"));
        var state = new BaselineState(entity, BaselineProfile.empty());
        assertThat(service.alreadyFolded(state, Instant.parse("2026-01-01T10:00:00Z"))).isFalse();
    }

    @Test
    void foldSerializesFeaturesUpdatesSampleSizeAndPersists() {
        var entity = new BehaviorBaselineEntity();
        entity.setId(UUID.randomUUID());
        var state = new BaselineState(entity, BaselineProfile.empty());
        var ws = Instant.parse("2026-01-01T09:00:00Z");

        service.fold(state, window(5), ws, 90);

        var captor = ArgumentCaptor.forClass(BehaviorBaselineEntity.class);
        verify(repo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getSampleSize()).isEqualTo(1);
        assertThat(saved.getLastWindowStart()).isEqualTo(ws);
        assertThat(saved.getLastRefreshedAt()).isEqualTo(NOW);

        // The persisted JSON round-trips back to a profile carrying the folded window.
        var reparsed = objectMapper.readValue(saved.getFeatures(), BaselineProfile.class);
        assertThat(reparsed.windowsFolded()).isEqualTo(1);
        assertThat(reparsed.scalar(BaselineProfile.QUERY_COUNT)).containsExactly(5.0);
    }

    @Test
    void foldTrimsToMaxSamples() {
        var seed = BaselineProfile.empty();
        // Seed three windows already folded, then fold more under a maxSamples cap of 2.
        var scalars = new LinkedHashMap<String, List<Double>>();
        scalars.put(BaselineProfile.QUERY_COUNT, List.of(1.0, 2.0, 3.0));
        var profile = new BaselineProfile(scalars, new long[24], Map.of(), Map.of(), 3);
        var entity = new BehaviorBaselineEntity();
        entity.setId(UUID.randomUUID());
        var state = new BaselineState(entity, profile);

        service.fold(state, window(9), Instant.parse("2026-01-01T09:00:00Z"), 2);

        var captor = ArgumentCaptor.forClass(BehaviorBaselineEntity.class);
        verify(repo).save(captor.capture());
        var reparsed = objectMapper.readValue(captor.getValue().getFeatures(), BaselineProfile.class);
        // FIFO trim to 2 keeps the two most recent observations: 3.0 (existing) and 9.0 (new).
        assertThat(reparsed.scalar(BaselineProfile.QUERY_COUNT)).containsExactly(3.0, 9.0);
        assertThat(seed.windowsFolded()).isZero();
    }

    private BehaviorBaselineEntity baselineEntity(String features) {
        var entity = new BehaviorBaselineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setUserId(USER);
        entity.setDatasourceId(DS);
        entity.setFeatures(features);
        return entity;
    }
}
