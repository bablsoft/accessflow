package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.proxy.api.EffectiveRowCap;
import com.bablsoft.accessflow.proxy.api.RowCapResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationRowLimitResolverTest {

    private static final int GLOBAL_CEILING = 10_000;

    @Mock DatasourceUserPermissionLookupService lookupService;

    AttestationRowLimitResolver resolver;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID reviewedId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RowCapResolver rowCapResolver = new RowCapResolver() {
            @Override
            public EffectiveRowCap resolve(Integer override, int datasourceCap) {
                return EffectiveRowCap.of(override, datasourceCap, GLOBAL_CEILING);
            }

            @Override
            public int globalCeiling() {
                return GLOBAL_CEILING;
            }
        };
        resolver = new AttestationRowLimitResolver(lookupService, rowCapResolver);
    }

    @Test
    void theReviewedGrantSetsTheLimitWhenItIsTheSmallestBound() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, 500);
        givenContributions(List.of(direct), 500);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(500));

        assertThat(evidence).isEqualTo(new AttestationRowLimitResolver.RowLimitEvidence(500, 500,
                "grant"));
    }

    @Test
    void aLowerGroupOverrideIsNamedAsTheSource() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, 500);
        var group = contribution(DatasourcePermissionSourceKind.GROUP, "analysts", 100);
        givenContributions(List.of(direct, group), 100);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(500));

        assertThat(evidence).isEqualTo(new AttestationRowLimitResolver.RowLimitEvidence(500, 100,
                "group:analysts"));
    }

    @Test
    void theDatasourceCapClampsAnOverrideAboveIt() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, 5_000);
        givenContributions(List.of(direct), 5_000);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(5_000));

        assertThat(evidence).isEqualTo(new AttestationRowLimitResolver.RowLimitEvidence(5_000,
                1_000, "datasource_cap"));
    }

    @Test
    void theGlobalCeilingClampsWhenItIsTheLowestBound() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, 50_000);
        givenContributions(List.of(direct), 50_000);

        var evidence = resolver.forDatasource(datasourceId, 100_000).evaluate(reviewed(50_000));

        assertThat(evidence).isEqualTo(new AttestationRowLimitResolver.RowLimitEvidence(50_000,
                GLOBAL_CEILING, "global_ceiling"));
    }

    @Test
    void aTieBetweenTheGrantAndAGroupCreditsTheReviewedGrant() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, 100);
        var group = contribution(DatasourcePermissionSourceKind.GROUP, "analysts", 100);
        givenContributions(List.of(direct, group), 100);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(100));

        assertThat(evidence.source()).isEqualTo("grant");
    }

    @Test
    void noOverrideAnywhereFallsToTheDatasourceCap() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, null);
        givenContributions(List.of(direct), null);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(null));

        assertThat(evidence).isEqualTo(new AttestationRowLimitResolver.RowLimitEvidence(null,
                1_000, "datasource_cap"));
    }

    @Test
    void aSubjectWithNoLiveGrantIsRecordedAsSuchNotAsTheDatasourceCap() {
        when(lookupService.findContributionsForDatasource(datasourceId)).thenReturn(List.of());

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(300));

        assertThat(evidence).isEqualTo(new AttestationRowLimitResolver.RowLimitEvidence(300, null,
                "no_live_grant"));
        verify(lookupService, never()).mergeContributions(List.of());
    }

    @Test
    void anExpiredReviewedGrantIsNotCreditedOnATieWithALiveGroup() {
        // The reviewed direct grant expired, so only the group contributes — at the same value.
        var group = contribution(DatasourcePermissionSourceKind.GROUP, "analysts", 100);
        givenContributions(List.of(group), 100);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(100));

        assertThat(evidence.source()).isEqualTo("group:analysts");
    }

    @Test
    void tiedGroupsAreCreditedByName() {
        var zeta = contribution(DatasourcePermissionSourceKind.GROUP, "zeta", 100);
        var alpha = contribution(DatasourcePermissionSourceKind.GROUP, "alpha", 100);
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, null, 500);
        givenContributions(List.of(direct, zeta, alpha), 100);

        var evidence = resolver.forDatasource(datasourceId, 1_000).evaluate(reviewed(500));

        assertThat(evidence.source()).isEqualTo("group:alpha");
    }

    @Test
    void contributionsAreLoadedOncePerDatasource() {
        when(lookupService.findContributionsForDatasource(datasourceId)).thenReturn(List.of());

        var limits = resolver.forDatasource(datasourceId, 1_000);
        limits.evaluate(reviewed(null));
        limits.evaluate(reviewed(null));

        verify(lookupService, times(1)).findContributionsForDatasource(datasourceId);
        verify(lookupService, never()).findContributions(userId, datasourceId);
    }

    private void givenContributions(List<DatasourcePermissionContribution> parts,
                                    Integer mergedOverride) {
        when(lookupService.findContributionsForDatasource(datasourceId)).thenReturn(parts);
        var merged = mock(DatasourceUserPermissionView.class);
        when(merged.rowLimitOverride()).thenReturn(mergedOverride);
        when(lookupService.mergeContributions(parts)).thenReturn(Optional.of(merged));
    }

    private DatasourcePermissionContribution contribution(DatasourcePermissionSourceKind kind,
                                                          String groupName, Integer rowLimit) {
        var sourceId = kind == DatasourcePermissionSourceKind.DIRECT ? reviewedId : UUID.randomUUID();
        return new DatasourcePermissionContribution(kind, sourceId, userId, datasourceId,
                groupName == null ? null : UUID.randomUUID(), groupName, true, false, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                rowLimit, null, null, null);
    }

    private DatasourcePermissionView reviewed(Integer rowLimit) {
        return new DatasourcePermissionView(reviewedId, datasourceId, userId,
                "u@example.com", "User", true, false, false, false, rowLimit, null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                UUID.randomUUID(), Instant.now());
    }
}
