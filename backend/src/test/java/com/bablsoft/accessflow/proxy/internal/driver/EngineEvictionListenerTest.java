package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EngineEvictionListenerTest {

    private final QueryEngineCatalog engineCatalog = mock(QueryEngineCatalog.class);
    private final EngineEvictionListener listener = new EngineEvictionListener(engineCatalog);

    @Test
    void configChangeEvictsDatasourceFromLoadedEngines() {
        var id = UUID.randomUUID();
        listener.onConfigChanged(new DatasourceConfigChangedEvent(id));
        verify(engineCatalog).evictDatasource(id);
    }

    @Test
    void deactivationEvictsDatasourceFromLoadedEngines() {
        var id = UUID.randomUUID();
        listener.onDeactivated(new DatasourceDeactivatedEvent(id));
        verify(engineCatalog).evictDatasource(id);
    }
}
