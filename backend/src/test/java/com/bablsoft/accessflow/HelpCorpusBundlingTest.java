package com.bablsoft.accessflow;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code help-corpus/} resource block in pom.xml (issue #900). maven-resources-plugin
 * silently skips a {@code <resource>} whose directory does not exist, so a mis-edited property, a
 * renamed folder or a build from a tree without the bundle would otherwise package a jar with no
 * corpus in it — and succeed. Nothing consumes the corpus yet, so this test is the only thing
 * standing between that mistake and a green build.
 */
class HelpCorpusBundlingTest {

    private static String readClasspath(String name) throws Exception {
        try (InputStream in = HelpCorpusBundlingTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("classpath resource %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bundlesTheGeneratedCorpusOnTheClasspath() throws Exception {
        assertThat(readClasspath("help-corpus/corpus.jsonl")).isNotBlank();
        assertThat(readClasspath("help-corpus/manifest.json")).contains("\"corpusVersion\"");
        assertThat(readClasspath("help-corpus/quick-reference.txt")).isNotBlank();
    }

    @Test
    void manifestDigestMatchesTheBundledCorpus() throws Exception {
        var corpus = readClasspath("help-corpus/corpus.jsonl");
        var digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(corpus.getBytes(StandardCharsets.UTF_8)));

        assertThat(readClasspath("help-corpus/manifest.json"))
                .as("manifest sha256 and corpusVersion must describe the corpus shipped beside them")
                .contains("\"sha256\": \"" + digest + "\"")
                .contains("\"corpusVersion\": \"" + digest.substring(0, 12) + "\"");
    }

    @Test
    void doesNotBundleTheAuthoringReadme() {
        assertThat(HelpCorpusBundlingTest.class.getClassLoader().getResource("help-corpus/README.md"))
                .as("README.md is excluded by the pom resource block")
                .isNull();
    }
}
