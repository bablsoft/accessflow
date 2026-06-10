package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Evicts the cached {@link MongoClient} for a datasource when its connection config changes or it is
 * deactivated — the MongoDB counterpart to {@code DatasourcePoolEvictionListener}.
 */
@Component
@RequiredArgsConstructor
class MongoClientEvictionListener {

    private final MongoClientManager clientManager;

    @ApplicationModuleListener
    void onConfigChanged(DatasourceConfigChangedEvent event) {
        clientManager.evict(event.datasourceId());
    }

    @ApplicationModuleListener
    void onDeactivated(DatasourceDeactivatedEvent event) {
        clientManager.evict(event.datasourceId());
    }
}
