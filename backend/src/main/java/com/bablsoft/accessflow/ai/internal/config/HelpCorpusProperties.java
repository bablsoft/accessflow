package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Optional remote refresh of the documentation corpus the help agent answers from (AF-907).
 *
 * <p><strong>Default off, deliberately.</strong> The corpus bundled in the jar is always exactly the
 * documentation for the version this install is running; a remote corpus describing a newer UI is a
 * worse failure than the staleness it fixes, because the agent states it confidently. Turning this on
 * buys one thing: picking up a documentation *correction* between application releases.
 *
 * <p>Kept apart from {@link HelpAgentProperties} rather than folded into it: that record is about
 * indexing the corpus this process already has, this one is about where the corpus comes from, and
 * folding them together would widen a bound record's canonical constructor for an unrelated concern.
 * Both bind under {@code accessflow.help-agent}; this one under its {@code corpus} sub-key.
 *
 * <p>Exactly one constructor: a second one on a {@code @ConfigurationProperties} record silently
 * unbinds every property, and the resulting error names pre-existing fields rather than the
 * constructor that broke them.
 *
 * @param remoteRefreshEnabled fetch a newer corpus from the release index at startup. Off by default.
 * @param indexUrl             the pointer file listing the newest published corpus —
 *                             {@code {version, corpusVersion, url, sha256}}. Only GA releases move it.
 * @param cacheDir             where verified corpus archives are kept, so a restart does not
 *                             re-download one. Under Helm this is a sub-directory of the driver-cache
 *                             volume, because {@code ~/.accessflow} is not writable as {@code uid 1000}.
 * @param offline              when true no request leaves the process for a corpus, whatever
 *                             {@code remoteRefreshEnabled} says — the air-gapped switch, matching
 *                             {@code accessflow.drivers.offline}.
 */
@ConfigurationProperties("accessflow.help-agent.corpus")
public record HelpCorpusProperties(
        Boolean remoteRefreshEnabled,
        String indexUrl,
        Path cacheDir,
        Boolean offline) {

    static final String DEFAULT_INDEX_URL =
            "https://bablsoft.github.io/accessflow/help-corpus/help-corpus-index.json";

    public HelpCorpusProperties {
        if (remoteRefreshEnabled == null) {
            remoteRefreshEnabled = Boolean.FALSE;
        }
        if (indexUrl == null || indexUrl.isBlank()) {
            indexUrl = DEFAULT_INDEX_URL;
        }
        if (cacheDir == null) {
            cacheDir = defaultCacheDir();
        }
        if (offline == null) {
            offline = Boolean.FALSE;
        }
    }

    private static Path defaultCacheDir() {
        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Paths.get(".accessflow", "help-corpus");
        }
        return Paths.get(home, ".accessflow", "help-corpus");
    }
}
