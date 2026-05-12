package com.bablsoft.accessflow.core.api;

import java.sql.Driver;

public record ResolvedDriver(Driver driver, ClassLoader classLoader, String driverClassName) {
}
