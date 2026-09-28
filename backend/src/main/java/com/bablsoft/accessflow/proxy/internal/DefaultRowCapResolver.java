package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.proxy.api.EffectiveRowCap;
import com.bablsoft.accessflow.proxy.api.RowCapResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class DefaultRowCapResolver implements RowCapResolver {

    private final ProxyPoolProperties properties;

    @Override
    public EffectiveRowCap resolve(Integer override, int datasourceCap) {
        return EffectiveRowCap.of(override, datasourceCap, globalCeiling());
    }

    @Override
    public int globalCeiling() {
        return properties.execution().maxRows();
    }
}
