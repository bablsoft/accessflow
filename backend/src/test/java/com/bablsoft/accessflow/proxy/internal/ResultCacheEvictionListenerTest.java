package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.events.DatasourceCacheConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResultCacheEvictionListenerTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Mock
    private SelectResultCache resultCache;

    @InjectMocks
    private ResultCacheEvictionListener listener;

    @Test
    void configChangePurgesDatasourceCache() {
        listener.onConfigChanged(new DatasourceConfigChangedEvent(datasourceId));
        verify(resultCache).invalidateAll(datasourceId);
    }

    @Test
    void deactivationPurgesDatasourceCache() {
        listener.onDeactivated(new DatasourceDeactivatedEvent(datasourceId));
        verify(resultCache).invalidateAll(datasourceId);
    }

    @Test
    void cacheConfigChangePurgesDatasourceCache() {
        listener.onCacheConfigChanged(new DatasourceCacheConfigChangedEvent(datasourceId));
        verify(resultCache).invalidateAll(datasourceId);
    }
}
