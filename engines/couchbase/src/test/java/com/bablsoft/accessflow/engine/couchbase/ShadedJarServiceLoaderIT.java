package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.QueryEngine;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ServiceLoader;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the SHADED plugin JAR (built by the package phase, hence failsafe) through the same
 * mechanics the host's engine catalog uses: an isolated {@link URLClassLoader} chain — platform
 * loader &rarr; backend classes (the {@code core.api} SPI) &rarr; plugin JAR — with
 * {@link ServiceLoader} discovery, plus jar-content assertions (service registration present,
 * Reactor/Jackson relocated, no host classes, Couchbase SDK bundled).
 */
class ShadedJarServiceLoaderIT {

    private static final String SERVICE_FILE =
            "META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine";

    @Test
    void shadedJarContainsServiceRegistrationRelocatedDepsAndNoHostClasses() throws Exception {
        try (var jar = new JarFile(shadedJar().toFile())) {
            assertThat(jar.getEntry(SERVICE_FILE)).isNotNull();
            try (var in = jar.getInputStream(jar.getEntry(SERVICE_FILE))) {
                assertThat(new String(in.readAllBytes()).strip())
                        .isEqualTo(CouchbaseQueryEngine.class.getName());
            }
            var entries = new ArrayList<String>();
            jar.stream().forEach(e -> entries.add(e.getName()));
            assertThat(entries).anyMatch(e -> e.startsWith("com/couchbase/client/java/"));
            assertThat(entries).anyMatch(e -> e.startsWith("com/couchbase/client/core/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/couchbase/shaded/reactor/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/couchbase/shaded/org/reactivestreams/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/couchbase/shaded/tools/jackson/"));
            // Unrelocated Reactor/Jackson must not leak in (the host carries its own reactor-core
            // via Lettuce), and no host (core.api) classes either — those resolve from the parent
            // (application) classloader at runtime.
            assertThat(entries).noneMatch(e -> e.startsWith("reactor/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/reactivestreams/"));
            assertThat(entries).noneMatch(e -> e.startsWith("tools/jackson/"));
            assertThat(entries).noneMatch(e -> e.startsWith("com/fasterxml/jackson/")
                    && !e.startsWith("com/bablsoft/"));
            assertThat(entries).noneMatch(e -> e.startsWith("com/bablsoft/accessflow/core/"));
        }
    }

    @Test
    void serviceLoaderDiscoversEngineFromShadedJarInIsolatedClassLoaderChain() throws Exception {
        // Parent layer: ONLY the backend classes (where the QueryEngine SPI lives) on top of the
        // platform loader — no test classpath, so nothing can leak into the discovery.
        var backendClasses = Path.of(QueryEngine.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var slf4j = Path.of(org.slf4j.Logger.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var parent = new URLClassLoader("test-host",
                new URL[]{backendClasses.toUri().toURL(), slf4j.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        try (var plugin = new URLClassLoader("accessflow-engine-couchbase",
                new URL[]{shadedJar().toUri().toURL()}, parent)) {
            var spi = Class.forName("com.bablsoft.accessflow.core.api.QueryEngine", false, parent);
            Object found = null;
            for (var provider : ServiceLoader.load(spi, plugin)) {
                found = provider;
            }
            assertThat(found).isNotNull();
            assertThat(found.getClass().getName())
                    .isEqualTo("com.bablsoft.accessflow.engine.couchbase.CouchbaseQueryEngine");
            assertThat(found.getClass().getClassLoader()).isSameAs(plugin);
            assertThat(spi.getMethod("engineId").invoke(found)).isEqualTo("couchbase");
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
