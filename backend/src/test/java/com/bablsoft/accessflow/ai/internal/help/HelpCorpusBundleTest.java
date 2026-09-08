package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every one of these failure modes is silent without a check: a truncated download, a hand-edited
 * manifest and a bundle from a newer AccessFlow all produce a {@code corpus.jsonl} that parses. The
 * outcome would be an agent answering confidently from documentation that is not what it claims to
 * be, which is worse than an agent that refuses to start.
 *
 * <p>The other half of the contract is that none of it throws: loading runs in a constructor at
 * startup, and an install that never enables the help agent must not be refused a boot over a
 * resource it will never read.
 */
class HelpCorpusBundleTest {

    private static final String FIXTURES = "classpath:help-corpus-fixtures/";

    private static HelpCorpusBundle bundleFrom(String fixture) {
        return new HelpCorpusBundle(new DefaultResourceLoader(), FIXTURES + fixture + "/");
    }

    @Test
    void loadsAVerifiedBundle() {
        var bundle = bundleFrom("good");

        assertThat(bundle.available()).isTrue();
        assertThat(bundle.loadError()).isNull();
        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
        assertThat(bundle.chunkCount()).isEqualTo(2);
        assertThat(bundle.quickReference()).contains("The query lifecycle");
    }

    @Test
    void exposesEveryFieldOfEachChunk() {
        var chunk = bundleFrom("good").chunks().getFirst();

        assertThat(chunk.id()).isEqualTo("aaaa0000bbbb1111");
        assertThat(chunk.path()).isEqualTo("website/docs/index.html");
        assertThat(chunk.url()).isEqualTo("https://accessflow.io/docs/");
        assertThat(chunk.anchor()).isEqualTo("review-plans");
        assertThat(chunk.title()).isEqualTo("Review plans");
        assertThat(chunk.section()).isEqualTo("Documentation");
        assertThat(chunk.order()).isZero();
        assertThat(chunk.tokens()).isEqualTo(12);
        assertThat(chunk.text()).startsWith("AccessFlow Docs > Documentation > Review plans");
    }

    @Test
    void loadsTheCorpusActuallyShippedInTheJar() {
        // The real bundle, through the same verification the application uses at startup.
        var bundle = new HelpCorpusBundle(new DefaultResourceLoader());

        assertThat(bundle.available()).isTrue();
        assertThat(bundle.corpusVersion()).hasSize(12);
        assertThat(bundle.chunkCount()).isGreaterThan(100);
        assertThat(bundle.quickReference()).isNotBlank();
    }

    @Test
    void refusesACorpusThatDoesNotMatchItsChecksum() {
        var bundle = bundleFrom("bad-checksum");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("corpus.jsonl digest", "does not match the manifest");
        assertThat(bundle.corpusVersion()).isNull();
        assertThat(bundle.chunks()).isEmpty();
    }

    @Test
    void refusesAManifestWhoseCorpusVersionIsNotTheDigestPrefix() {
        var bundle = bundleFrom("bad-corpus-version");

        // Catches a hand-edited manifest: the digest still matches, so only this check sees it.
        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("corpusVersion 'deadbeefcafe'");
    }

    @Test
    void refusesABundleFromANewerAccessFlow() {
        var bundle = bundleFrom("future-schema");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError())
                .contains("schemaVersion 2 is newer than the supported 1", "upgrade AccessFlow");
    }

    @Test
    void refusesACorpusWithADifferentChunkCountThanDeclared() {
        var bundle = bundleFrom("count-mismatch");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("holds 2 chunks but the manifest declares 99");
    }

    @Test
    void refusesAQuickReferenceThatDoesNotMatchItsChecksum() {
        var bundle = bundleFrom("bad-quick-reference");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("quick-reference.txt digest");
    }

    @Test
    void refusesAnEmptyCorpusFile() {
        var bundle = bundleFrom("empty-corpus");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("corpus.jsonl", "is empty");
    }

    @Test
    void reportsAMissingManifestWithoutThrowing() {
        var bundle = bundleFrom("no-manifest");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("manifest.json is missing");
    }

    @Test
    void reportsAnEntirelyMissingBundleWithoutThrowing() {
        var bundle = bundleFrom("does-not-exist");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.loadError()).contains("manifest.json is missing");
        assertThat(bundle.chunkCount()).isZero();
    }

    @Test
    void activatesARefreshedCorpusThatPassesEveryCheck() throws IOException {
        var bundle = new HelpCorpusBundle(new DefaultResourceLoader());
        var bundled = bundle.bundledCorpusVersion();

        var activated = bundle.activateRefreshed(TarGzFixtures.bundleFiles("good"));

        assertThat(activated).isEqualTo("0d746008cf42");
        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
        assertThat(bundle.chunkCount()).isEqualTo(2);
        assertThat(bundle.quickReference()).contains("The query lifecycle");
        // What the jar shipped stays reportable, so a log line can say what was replaced.
        assertThat(bundle.bundledCorpusVersion()).isEqualTo(bundled).isNotEqualTo(activated);
    }

    @Test
    void keepsTheActiveCorpusWhenARefreshedOneFailsVerification() throws IOException {
        var bundle = new HelpCorpusBundle(new DefaultResourceLoader());
        var bundled = bundle.corpusVersion();

        assertThatThrownBy(() -> bundle.activateRefreshed(TarGzFixtures.bundleFiles("bad-checksum")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corpus.jsonl digest");

        // The whole guarantee of the optional refresh: a bad remote corpus costs nothing.
        assertThat(bundle.available()).isTrue();
        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void refusesARefreshedCorpusFromANewerAccessFlow() throws IOException {
        var bundle = new HelpCorpusBundle(new DefaultResourceLoader());

        assertThatThrownBy(
                () -> bundle.activateRefreshed(TarGzFixtures.bundleFiles("future-schema")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schemaVersion 2 is newer than the supported 1");
    }

    @Test
    void refusesARefreshedCorpusMissingOneOfItsThreeFiles() throws IOException {
        var bundle = new HelpCorpusBundle(new DefaultResourceLoader());
        Map<String, byte[]> incomplete = new HashMap<>(TarGzFixtures.bundleFiles("good"));
        incomplete.remove(HelpCorpusBundle.QUICK_REFERENCE_FILE);

        assertThatThrownBy(() -> bundle.activateRefreshed(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quick-reference.txt is missing from the published archive");
        assertThat(bundle.available()).isTrue();
    }

    @Test
    void canRescueAnInstallWhoseBundledCorpusIsUnusable() throws IOException {
        var bundle = bundleFrom("bad-checksum");
        assertThat(bundle.available()).isFalse();

        bundle.activateRefreshed(TarGzFixtures.bundleFiles("good"));

        assertThat(bundle.available()).isTrue();
        assertThat(bundle.loadError()).isNull();
        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
    }
}
