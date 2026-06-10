package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Fans datasource config-change / deactivation events out to every loaded {@link
 * com.bablsoft.accessflow.core.api.QueryEngine} so engines drop their cached native clients — the
 * engine-plugin counterpart to {@code DatasourcePoolEvictionListener}. Never triggers a plugin
 * download: only already-loaded engines are notified.
 */
@Component
@RequiredArgsConstructor
class EngineEvictionListener {

    private final QueryEngineCatalog engineCatalog;

    @ApplicationModuleListener
    void onConfigChanged(DatasourceConfigChangedEvent event) {
        engineCatalog.evictDatasource(event.datasourceId());
    }

    @ApplicationModuleListener
    void onDeactivated(DatasourceDeactivatedEvent event) {
        engineCatalog.evictDatasource(event.datasourceId());
    }
}
