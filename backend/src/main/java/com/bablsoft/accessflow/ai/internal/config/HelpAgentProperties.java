package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deployment-wide tunables for indexing the bundled documentation corpus (AF-902). Everything an
 * admin chooses — whether the agent is on, which {@code ai_config} it uses, top-K, thresholds — lives
 * on the per-organization {@code help_agent_config} row; these three are operator concerns that
 * cannot be per-organization because they are about the process, not the tenant.
 *
 * <p>Exactly one constructor: a second one would silently unbind every property, and the error names
 * pre-existing fields rather than the constructor.
 *
 * @param indexOnStartup    index enabled organizations once the application is ready. Turn off to
 *                          keep a slow local embedding backend from doing minutes of work on every
 *                          restart; the settings page's re-index button still works.
 * @param indexBatchSize    chunks per {@code vectorStore.add(...)} call — the whole ~510-chunk corpus
 *                          in one call upsets both OpenAI and Ollama
 * @param indexLockAtMostFor how long one replica may hold the cluster-wide indexing lock. Set well
 *                          above the expected pass duration: the Redis key expires after it even if
 *                          the JVM crashes mid-pass, and CPU-only Ollama embeds ~510 chunks in
 *                          minutes, not seconds.
 */
@ConfigurationProperties("accessflow.help-agent")
public record HelpAgentProperties(
        Boolean indexOnStartup,
        Integer indexBatchSize,
        Duration indexLockAtMostFor) {

    private static final int DEFAULT_INDEX_BATCH_SIZE = 64;
    private static final Duration DEFAULT_INDEX_LOCK_AT_MOST_FOR = Duration.ofMinutes(30);

    public HelpAgentProperties {
        if (indexOnStartup == null) {
            indexOnStartup = Boolean.TRUE;
        }
        if (indexBatchSize == null || indexBatchSize < 1) {
            indexBatchSize = DEFAULT_INDEX_BATCH_SIZE;
        }
        if (indexLockAtMostFor == null || indexLockAtMostFor.isZero()
                || indexLockAtMostFor.isNegative()) {
            indexLockAtMostFor = DEFAULT_INDEX_LOCK_AT_MOST_FOR;
        }
    }
}
