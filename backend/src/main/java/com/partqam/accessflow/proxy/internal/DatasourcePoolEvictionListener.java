package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.events.DatasourceConfigChangedEvent;
import com.partqam.accessflow.core.events.DatasourceDeactivatedEvent;
import com.partqam.accessflow.proxy.api.DatasourceConnectionPoolManager;
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
