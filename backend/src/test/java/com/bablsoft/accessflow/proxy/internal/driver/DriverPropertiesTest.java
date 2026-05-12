package com.bablsoft.accessflow.proxy.internal.driver;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class DriverPropertiesTest {

    @Test
    void appliesDefaultsForNullFields() {
        var props = new DriverProperties(null, null, false);

        var home = System.getProperty("user.home");
        var expected = home == null || home.isBlank()
                ? Paths.get(".accessflow", "drivers")
                : Paths.get(home, ".accessflow", "drivers");
        assertThat(props.cacheDir()).isEqualTo(expected);
        assertThat(props.repositoryUrl()).isEqualTo("https://repo1.maven.org/maven2");
        assertThat(props.offline()).isFalse();
    }

    @Test
    void appliesDefaultRepositoryUrlForBlank() {
        var props = new DriverProperties(Path.of("/cache"), "  ", false);

        assertThat(props.repositoryUrl()).isEqualTo("https://repo1.maven.org/maven2");
    }

    @Test
    void stripsTrailingSlashFromRepositoryUrl() {
        var props = new DriverProperties(Path.of("/cache"), "https://nexus.example.com/maven2/",
                false);

        assertThat(props.repositoryUrl()).isEqualTo("https://nexus.example.com/maven2");
    }

    @Test
    void preservesExplicitOfflineTrue() {
        var props = new DriverProperties(Path.of("/cache"), "https://nexus.example.com/maven2",
                true);

        assertThat(props.offline()).isTrue();
    }
}
