package com.bablsoft.accessflow.proxy.internal.driver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties("accessflow.drivers")
public record DriverProperties(Path cacheDir, String repositoryUrl, boolean offline) {

    public DriverProperties {
        if (cacheDir == null) {
            cacheDir = defaultCacheDir();
        }
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            repositoryUrl = "https://repo1.maven.org/maven2";
        }
        repositoryUrl = stripTrailingSlash(repositoryUrl);
    }

    private static Path defaultCacheDir() {
        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Paths.get(".accessflow", "drivers");
        }
        return Paths.get(home, ".accessflow", "drivers");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
