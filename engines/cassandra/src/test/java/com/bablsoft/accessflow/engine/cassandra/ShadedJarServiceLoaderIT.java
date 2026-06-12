package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.QueryEngine;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the SHADED plugin JAR (built by the package phase, hence failsafe) through the same
 * mechanics the host's engine catalog uses: an isolated {@link URLClassLoader} chain — platform
 * loader &rarr; backend classes (the {@code core.api} SPI) &rarr; plugin JAR — with
 * {@link ServiceLoader} discovery, plus jar-content assertions (both service registrations present,
 * Netty/Typesafe Config/HdrHistogram relocated, no host classes, DataStax driver bundled,
 * reference.conf merged).
 */
class ShadedJarServiceLoaderIT {

    private static final String SERVICE_FILE =
            "META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine";

    @Test
    void shadedJarContainsBothRegistrationsRelocatedDepsAndNoHostClasses() throws Exception {
        try (var jar = new JarFile(shadedJar().toFile())) {
            assertThat(jar.getEntry(SERVICE_FILE)).isNotNull();
            try (var in = jar.getInputStream(jar.getEntry(SERVICE_FILE))) {
                assertThat(new String(in.readAllBytes()).strip().lines().toList())
                        .containsExactlyInAnyOrder(
                                CassandraQueryEngine.class.getName(),
                                ScyllaDbQueryEngine.class.getName());
            }
            // The DataStax driver loads its defaults from reference.conf; the merge is mandatory.
            assertThat(jar.getEntry("reference.conf")).isNotNull();

            var entries = new ArrayList<String>();
            jar.stream().forEach(e -> entries.add(e.getName()));
            // Driver API bundled un-relocated (the engine compiles against it).
            assertThat(entries).anyMatch(e -> e.startsWith("com/datastax/oss/driver/"));
            // Host-shared libraries relocated under the plugin namespace.
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/cassandra/shaded/io/netty/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/cassandra/shaded/com/typesafe/config/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/cassandra/shaded/org/HdrHistogram/"));
            // Un-relocated Netty/Typesafe Config/HdrHistogram must not leak (the host carries its
            // own), and no host (core.api) classes either — those resolve from the parent loader.
            assertThat(entries).noneMatch(e -> e.startsWith("io/netty/"));
            assertThat(entries).noneMatch(e -> e.startsWith("com/typesafe/config/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/HdrHistogram/"));
            assertThat(entries).noneMatch(e -> e.startsWith("com/bablsoft/accessflow/core/"));
        }
    }

    @Test
    void serviceLoaderDiscoversBothEnginesFromShadedJarInIsolatedClassLoaderChain() throws Exception {
        // Parent layer: ONLY the backend classes (where the QueryEngine SPI lives) on top of the
        // platform loader — no test classpath, so nothing can leak into the discovery.
        var backendClasses = Path.of(QueryEngine.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var slf4j = Path.of(org.slf4j.Logger.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var parent = new URLClassLoader("test-host",
                new URL[]{backendClasses.toUri().toURL(), slf4j.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        try (var plugin = new URLClassLoader("accessflow-engine-cassandra",
                new URL[]{shadedJar().toUri().toURL()}, parent)) {
            var spi = Class.forName("com.bablsoft.accessflow.core.api.QueryEngine", false, parent);
            Set<String> engineIds = new LinkedHashSet<>();
            for (var provider : ServiceLoader.load(spi, plugin)) {
                assertThat(provider.getClass().getClassLoader()).isSameAs(plugin);
                engineIds.add((String) spi.getMethod("engineId").invoke(provider));
            }
            assertThat(engineIds).containsExactlyInAnyOrder("cassandra", "scylladb");
        } finally {
            parent.close();
        }
    }

    private static Path shadedJar() throws Exception {
        var target = Path.of("target");
        try (var stream = Files.list(target)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith("-all.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Shaded jar not found in target/ — run mvn package first"));
        }
    }
}
