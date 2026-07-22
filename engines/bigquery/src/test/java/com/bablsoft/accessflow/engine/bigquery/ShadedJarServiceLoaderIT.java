package com.bablsoft.accessflow.engine.bigquery;

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
 * {@link ServiceLoader} discovery, plus jar-content assertions: the single registration present,
 * EVERY bundled third-party package relocated under the plugin namespace (google stack,
 * opencensus, opentelemetry, grpc context, threeten, jackson, json, commons-codec), and no
 * gRPC-runtime / Netty / Arrow / host / Spring classes leaking.
 */
class ShadedJarServiceLoaderIT {

    private static final String SERVICE_FILE =
            "META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine";
    private static final String SHADED_PREFIX = "com/bablsoft/accessflow/engine/bigquery/shaded/";

    @Test
    void shadedJarContainsRegistrationRelocatedDepsAndNoLeaks() throws Exception {
        try (var jar = new JarFile(shadedJar().toFile())) {
            assertThat(jar.getEntry(SERVICE_FILE)).isNotNull();
            try (var in = jar.getInputStream(jar.getEntry(SERVICE_FILE))) {
                assertThat(new String(in.readAllBytes()).strip().lines().toList())
                        .containsExactly(BigQueryQueryEngine.class.getName());
            }

            var entries = new ArrayList<String>();
            jar.stream().forEach(e -> entries.add(e.getName()));

            // The BigQuery client and its stack live under the plugin's shaded namespace.
            assertThat(entries).anyMatch(e -> e.startsWith(
                    SHADED_PREFIX + "com/google/cloud/bigquery/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    SHADED_PREFIX + "com/google/auth/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    SHADED_PREFIX + "io/opencensus/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    SHADED_PREFIX + "org/threeten/"));

            // No unrelocated third-party packages — nothing may leak across the classloader
            // boundary (the host and other plugins carry their own copies of these).
            assertThat(entries).noneMatch(e -> e.startsWith("com/google/"));
            assertThat(entries).noneMatch(e -> e.startsWith("io/opencensus/"));
            assertThat(entries).noneMatch(e -> e.startsWith("io/opentelemetry/"));
            assertThat(entries).noneMatch(e -> e.startsWith("io/grpc/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/threeten/"));
            assertThat(entries).noneMatch(e -> e.startsWith("com/fasterxml/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/json/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/apache/commons/"));

            // The excluded Storage-Read-API lane must not ride along: no gRPC runtime, no Netty,
            // no Arrow, no Apache HTTP — and no host / Spring classes (those resolve from the
            // parent loader).
            assertThat(entries).noneMatch(e -> e.contains("grpc/netty"));
            assertThat(entries).noneMatch(e -> e.startsWith("io/netty/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/apache/arrow/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/apache/http/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/conscrypt/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/springframework/"));
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
        try (var plugin = new URLClassLoader("accessflow-engine-bigquery",
                new URL[]{shadedJar().toUri().toURL()}, parent)) {
            var spi = Class.forName("com.bablsoft.accessflow.core.api.QueryEngine", false, parent);
            Set<String> engineIds = new LinkedHashSet<>();
            for (var provider : ServiceLoader.load(spi, plugin)) {
                assertThat(provider.getClass().getClassLoader()).isSameAs(plugin);
                engineIds.add((String) spi.getMethod("engineId").invoke(provider));
            }
            assertThat(engineIds).containsExactly("bigquery");
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
