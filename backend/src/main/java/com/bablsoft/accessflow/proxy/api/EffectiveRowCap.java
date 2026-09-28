package com.bablsoft.accessflow.proxy.api;

/**
 * The row cap the proxy enforces for one execution (#933), with the bound that set it (#946).
 * {@code override} is the merged grant override as given — possibly above {@code value} when the
 * datasource cap or global ceiling clamped it.
 */
public record EffectiveRowCap(int value, RowCapSource source, Integer override, int datasourceCap,
                              int globalCeiling) {

    /**
     * The one clamp: an override only ever lowers the cap — never above the datasource cap or the
     * global ceiling. On a tie the most specific bound is reported (override, then datasource).
     */
    public static EffectiveRowCap of(Integer override, int datasourceCap, int globalCeiling) {
        if (override != null && override <= datasourceCap && override <= globalCeiling) {
            return new EffectiveRowCap(override, RowCapSource.OVERRIDE, override, datasourceCap,
                    globalCeiling);
        }
        if (datasourceCap <= globalCeiling) {
            return new EffectiveRowCap(datasourceCap, RowCapSource.DATASOURCE_CAP, override,
                    datasourceCap, globalCeiling);
        }
        return new EffectiveRowCap(globalCeiling, RowCapSource.GLOBAL_CEILING, override,
                datasourceCap, globalCeiling);
    }
}
