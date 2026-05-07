package com.partqam.accessflow.core.api;

import java.util.List;

public interface DriverCatalogService {

    List<DriverTypeInfo> list();

    ResolvedDriver resolve(DbType dbType);
}
