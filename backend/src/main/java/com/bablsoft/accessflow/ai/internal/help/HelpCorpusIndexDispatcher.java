package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * The single asynchronous, cluster-safe seam in front of {@link HelpCorpusIndexer} (AF-902). Startup,
 * the configuration-change listeners and the admin re-index endpoint all go through it, so the two
 * rules that make indexing safe are stated once instead of three times:
 *
 * <ol>
 *   <li><strong>Never on the caller's thread.</strong> Embedding ~510 chunks is minutes of work on a
 *       CPU-only Ollama. On the startup thread it would delay readiness; on the request thread it
 *       would blow past the client's timeout with a 202 already promised.</li>
 *   <li><strong>Once across the cluster, per organization.</strong> {@code runLocked} is the right
 *       primitive here and {@code @SchedulerLock} is not — that annotation is tied to
 *       {@code @Scheduled}, and this work is event-driven. Without the lock, N replicas would each
 *       delete and re-add the same scope, racing each other's deletes and burning N times the
 *       embedding budget.</li>
 * </ol>
 *
 * <p><strong>The lock is per {@code help_agent_config}, not global.</strong> A pass takes minutes and
 * there is no retry queue, so a single deployment-wide lock would mean the second admin to enable the
 * agent — in an unrelated organization — simply lost, silently, until the next restart. Keying it on
 * the row makes contention mean what dropping it implies: the only pass that can lose is one for the
 * same organization, whose winner is doing exactly the same work. A loser logs at {@code INFO} and
 * does nothing.
 *
 * <p>Lock names share one ShedLock namespace with {@code @SchedulerLock} jobs, hence the prefix.
 */
@Component
public class HelpCorpusIndexDispatcher {

    static final String LOCK_PREFIX = "helpCorpusIndex:";

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusIndexDispatcher.class);

    private final HelpCorpusIndexer indexer;
    private final DistributedLockService distributedLockService;
    private final HelpAgentProperties properties;
    private final Executor executor;

    HelpCorpusIndexDispatcher(HelpCorpusIndexer indexer,
                              DistributedLockService distributedLockService,
                              HelpAgentProperties properties,
                              @Qualifier("helpCorpusIndexExecutor") Executor executor) {
        this.indexer = indexer;
        this.distributedLockService = distributedLockService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * Indexes every organization with the agent switched on, off-thread, each under its own lock. One
     * organization's unreachable provider neither stops nor blocks the next: {@code index} never
     * throws, and the locks are independent.
     */
    public void dispatchAll(boolean force) {
        dispatch("all enabled organizations", () -> {
            var ids = indexer.enabledConfigIds();
            if (ids.isEmpty()) {
                log.debug("No organization has the help agent enabled; nothing to index");
                return;
            }
            for (var id : ids) {
                indexLocked(id, force);
            }
            log.info("Help corpus indexing pass complete over {} enabled organization(s)", ids.size());
        });
    }

    /** Indexes one {@code help_agent_config} row, off-thread and once per cluster for that row. */
    public void dispatchOne(UUID helpAgentConfigId, boolean force) {
        dispatch("help_agent_config " + helpAgentConfigId, () -> indexLocked(helpAgentConfigId, force));
    }

    private void dispatch(String description, Runnable pass) {
        try {
            executor.execute(() -> run(description, pass));
        } catch (RejectedExecutionException shuttingDown) {
            log.debug("Help corpus indexing for {} was rejected by the executor (shutting down?)",
                    description, shuttingDown);
        }
    }

    private void run(String description, Runnable pass) {
        try {
            pass.run();
        } catch (RuntimeException e) {
            // Nothing is waiting on this thread, so an escape would only surface as an executor
            // stack trace with no context. The per-row failures are already in index_error.
            log.error("Help corpus indexing pass for {} failed", description, e);
        }
    }

    private void indexLocked(UUID helpAgentConfigId, boolean force) {
        boolean executed = distributedLockService.runLocked(lockName(helpAgentConfigId),
                properties.indexLockAtMostFor(), () -> indexer.index(helpAgentConfigId, force));
        if (!executed) {
            log.info("Another replica is already indexing help_agent_config {}; skipping",
                    helpAgentConfigId);
        }
    }

    static String lockName(UUID helpAgentConfigId) {
        return LOCK_PREFIX + helpAgentConfigId;
    }
}
