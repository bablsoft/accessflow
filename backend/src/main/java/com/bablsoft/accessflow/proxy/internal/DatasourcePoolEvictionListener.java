package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DatasourcePoolEvictionListener {

    private final DatasourceConnectionPoolManager connectionPoolManager;

    @ApplicationModuleListener
    void onConfigChanged(DatasourceConfigChangedEvent event) {
        connectionPoolManager.evict(event.datasourceId());
    }

    @ApplicationModuleListener
    void onDeactivated(DatasourceDeactivatedEvent event) {
        connectionPoolManager.evict(event.datasourceId());
    }
}
