package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.events.DatasourceCacheConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Purges the SELECT result cache for a datasource whenever its connection config changes, it is
 * deactivated, or its cache settings change (AF-457). Sibling of
 * {@link DatasourcePoolEvictionListener} — cache-setting changes deliberately do NOT evict pools.
 */
@Component
@RequiredArgsConstructor
class ResultCacheEvictionListener {

    private final SelectResultCache resultCache;

    @ApplicationModuleListener
    void onConfigChanged(DatasourceConfigChangedEvent event) {
        resultCache.invalidateAll(event.datasourceId());
    }

    @ApplicationModuleListener
    void onDeactivated(DatasourceDeactivatedEvent event) {
        resultCache.invalidateAll(event.datasourceId());
    }

    @ApplicationModuleListener
    void onCacheConfigChanged(DatasourceCacheConfigChangedEvent event) {
        resultCache.invalidateAll(event.datasourceId());
    }
}
