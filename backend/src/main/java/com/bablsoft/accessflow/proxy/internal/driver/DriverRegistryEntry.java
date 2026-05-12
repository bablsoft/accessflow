package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;

record DriverRegistryEntry(
        DbType dbType,
        String displayName,
        String iconUrl,
        int defaultPort,
        SslMode defaultSslMode,
        String jdbcUrlTemplate,
        String groupId,
        String artifactId,
        String version,
        String sha256,
        String driverClassName,
        boolean bundled) {

    String jarFileName() {
        return artifactId + "-" + version + ".jar";
    }

    String mavenPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + jarFileName();
    }
}
