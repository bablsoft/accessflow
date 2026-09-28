package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.proxy.api.EffectiveRowCap;
import com.bablsoft.accessflow.proxy.api.RowCapSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRowCapResolverTest {

    private final DefaultRowCapResolver resolver = new DefaultRowCapResolver(
            new ProxyPoolProperties(null, null, null, null, null,
                    new ProxyPoolProperties.Execution(500, null, null, null, null, null, null),
                    null));

    @Test
    void overrideBelowBothBoundsWins() {
        var cap = resolver.resolve(5, 100);

        assertThat(cap.value()).isEqualTo(5);
        assertThat(cap.source()).isEqualTo(RowCapSource.OVERRIDE);
        assertThat(cap.override()).isEqualTo(5);
        assertThat(cap.datasourceCap()).isEqualTo(100);
        assertThat(cap.globalCeiling()).isEqualTo(500);
    }

    @Test
    void overrideAboveDatasourceCapIsClampedToIt() {
        var cap = resolver.resolve(5000, 100);

        assertThat(cap.value()).isEqualTo(100);
        assertThat(cap.source()).isEqualTo(RowCapSource.DATASOURCE_CAP);
        assertThat(cap.override()).isEqualTo(5000);
    }

    @Test
    void noOverrideFallsBackToDatasourceCap() {
        var cap = resolver.resolve(null, 100);

        assertThat(cap.value()).isEqualTo(100);
        assertThat(cap.source()).isEqualTo(RowCapSource.DATASOURCE_CAP);
    }

    @Test
    void globalCeilingClampsEverythingAboveIt() {
        assertThat(resolver.resolve(null, 1000))
                .extracting(EffectiveRowCap::value, EffectiveRowCap::source)
                .containsExactly(500, RowCapSource.GLOBAL_CEILING);
        assertThat(resolver.resolve(800, 1000))
                .extracting(EffectiveRowCap::value, EffectiveRowCap::source)
                .containsExactly(500, RowCapSource.GLOBAL_CEILING);
        assertThat(resolver.globalCeiling()).isEqualTo(500);
    }

    @Test
    void tiesReportTheMostSpecificBound() {
        assertThat(EffectiveRowCap.of(100, 100, 100).source()).isEqualTo(RowCapSource.OVERRIDE);
        assertThat(EffectiveRowCap.of(null, 100, 100).source())
                .isEqualTo(RowCapSource.DATASOURCE_CAP);
    }
}
