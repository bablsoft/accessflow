package com.bablsoft.accessflow.engine.elasticsearch;

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
 * Jackson + both HttpComponents stacks relocated, no Netty / host-class leak, both driver namespaces
 * bundled).
 */
class ShadedJarServiceLoaderIT {

    private static final String SERVICE_FILE =
            "META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine";

    @Test
    void shadedJarContainsBothRegistrationsRelocatedDepsAndNoLeaks() throws Exception {
        try (var jar = new JarFile(shadedJar().toFile())) {
            assertThat(jar.getEntry(SERVICE_FILE)).isNotNull();
            try (var in = jar.getInputStream(jar.getEntry(SERVICE_FILE))) {
                assertThat(new String(in.readAllBytes()).strip().lines().toList())
                        .containsExactlyInAnyOrder(
                                ElasticsearchQueryEngine.class.getName(),
                                OpenSearchQueryEngine.class.getName());
            }

            var entries = new ArrayList<String>();
            jar.stream().forEach(e -> entries.add(e.getName()));
            // Both low-level driver namespaces bundled un-relocated (the host has neither).
            assertThat(entries).anyMatch(e -> e.startsWith("org/elasticsearch/client/"));
            assertThat(entries).anyMatch(e -> e.startsWith("org/opensearch/client/"));
            // Host-shared libraries relocated under the plugin namespace.
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/elasticsearch/shaded/tools/jackson/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/elasticsearch/shaded/org/apache/http/"));
            assertThat(entries).anyMatch(e -> e.startsWith(
                    "com/bablsoft/accessflow/engine/elasticsearch/shaded/org/apache/hc/"));
            // Un-relocated shared libs must not leak, no Netty, and no host (core.api) classes.
            assertThat(entries).noneMatch(e -> e.startsWith("tools/jackson/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/apache/http/"));
            assertThat(entries).noneMatch(e -> e.startsWith("org/apache/hc/"));
            assertThat(entries).noneMatch(e -> e.startsWith("io/netty/"));
            assertThat(entries).noneMatch(e -> e.startsWith("reactor/"));
            assertThat(entries).noneMatch(e -> e.startsWith("com/bablsoft/accessflow/core/"));
        }
    }

    @Test
    void serviceLoaderDiscoversBothEnginesFromShadedJarInIsolatedClassLoaderChain() throws Exception {
        var backendClasses = Path.of(QueryEngine.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var slf4j = Path.of(org.slf4j.Logger.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var parent = new URLClassLoader("test-host",
                new URL[]{backendClasses.toUri().toURL(), slf4j.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        try (var plugin = new URLClassLoader("accessflow-engine-elasticsearch",
                new URL[]{shadedJar().toUri().toURL()}, parent)) {
            var spi = Class.forName("com.bablsoft.accessflow.core.api.QueryEngine", false, parent);
            Set<String> engineIds = new LinkedHashSet<>();
            for (var provider : ServiceLoader.load(spi, plugin)) {
                assertThat(provider.getClass().getClassLoader()).isSameAs(plugin);
                engineIds.add((String) spi.getMethod("engineId").invoke(provider));
            }
            assertThat(engineIds).containsExactlyInAnyOrder("elasticsearch", "opensearch");
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
