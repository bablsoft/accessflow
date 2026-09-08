package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.RagStoreType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Embeds the bundled documentation corpus into the shared {@code vector_store} for each organization
 * that has the help agent switched on, and keeps it in step with the corpus content (AF-902).
 *
 * <p><strong>Re-ingestion is a string compare.</strong> {@code corpusVersion} is derived from the
 * corpus bytes, not from the application version, so a row whose {@code indexed_corpus_version}
 * already equals the bundle's is skipped — idempotently, across replicas and restarts. Keying on the
 * application version instead would both re-embed an unchanged corpus on every patch release and miss
 * two builds of the same version with different documentation.
 *
 * <p><strong>Delete, then add — never upsert.</strong> There is no stable primary key to upsert
 * against: a chunk's row id is assigned by the store, and a documentation edit changes a chunk's
 * content hash. Deleting this help configuration's whole scope first is the only way a re-index cannot
 * leave chunks from the previous corpus version behind, silently answering from documentation the
 * install no longer ships. The add is batched ({@code accessflow.help-agent.index-batch-size}) because
 * ~510 chunks in a single embedding call upsets both OpenAI and Ollama.
 *
 * <p><strong>A failure is recorded, never propagated.</strong> Ingestion runs off the request path, on
 * behalf of an organization whose admin is not watching; the outcome belongs in {@code index_error}
 * where the admin page can show it. {@code enabled} is deliberately left untouched — an operator
 * turned the agent on, and a transient embedding outage is not consent to turn it off.
 */
@Component
@RequiredArgsConstructor
public class HelpCorpusIndexer {

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusIndexer.class);
    private static final String PROBE_TEXT = "AccessFlow help corpus dimension probe";

    private final HelpCorpusBundle bundle;
    private final HelpAgentConfigRepository helpAgentConfigRepository;
    private final AiConfigRepository aiConfigRepository;
    private final RagComponentsFactory ragComponentsFactory;
    private final PgVectorAvailability pgVectorAvailability;
    private final HelpAgentProperties properties;
    private final Clock clock;

    /**
     * The indexer's work list: the {@code help_agent_config} id of every organization with the agent
     * switched on. Ids rather than entities, because each is indexed under its own lock in its own
     * unit of work, minutes apart — an entity loaded now would be stale by the time it was used.
     *
     * <p>An install where nobody enabled the help agent gets an empty list here and makes no further
     * call of any kind, which is the whole cost of shipping the feature switched off.
     */
    public List<UUID> enabledConfigIds() {
        return helpAgentConfigRepository.findAllByEnabledTrue().stream()
                .map(HelpAgentConfigEntity::getId)
                .toList();
    }

    /**
     * Indexes one {@code help_agent_config} row.
     *
     * @param force ignore the version equality check — what {@code POST /admin/help-agent/reindex}
     *              and a changed embedding model or store need
     * @return what happened; never throws
     */
    public HelpIndexOutcome index(UUID helpAgentConfigId, boolean force) {
        var row = helpAgentConfigRepository.findById(helpAgentConfigId).orElse(null);
        if (row == null) {
            log.debug("Help agent config {} no longer exists; nothing to index", helpAgentConfigId);
            return HelpIndexOutcome.SKIPPED_MISSING;
        }
        var skip = skipReason(row, force);
        if (skip != null) {
            return skip;
        }
        var aiConfig = aiConfigRepository
                .findByIdAndOrganizationId(row.getAiConfigId(), row.getOrganizationId())
                .orElse(null);
        if (aiConfig == null) {
            return fail(row, HelpIndexError.of("error.help_agent.index.ai_config_deleted"));
        }
        var unusable = retrievalBlocker(aiConfig);
        if (unusable != null) {
            return fail(row, unusable);
        }
        return ingest(row, aiConfig);
    }

    /** The cheap checks, in the order that avoids doing work to discover the next one is fatal. */
    private HelpIndexOutcome skipReason(HelpAgentConfigEntity row, boolean force) {
        if (!row.isEnabled()) {
            return HelpIndexOutcome.SKIPPED_DISABLED;
        }
        if (row.getAiConfigId() == null) {
            log.warn("Help agent is enabled for organization {} but bound to no AI configuration; "
                    + "skipping indexing", row.getOrganizationId());
            return HelpIndexOutcome.SKIPPED_UNBOUND;
        }
        if (!row.isRetrievalEnabled()) {
            log.debug("Documentation retrieval is off for organization {}; nothing to embed",
                    row.getOrganizationId());
            return HelpIndexOutcome.SKIPPED_RETRIEVAL_OFF;
        }
        if (!bundle.available()) {
            return fail(row, HelpIndexError.of("error.help_agent.index.corpus_unavailable",
                    bundle.loadError()));
        }
        if (!force && bundle.corpusVersion().equals(row.getIndexedCorpusVersion())) {
            log.debug("Organization {} already holds help corpus version {}", row.getOrganizationId(),
                    bundle.corpusVersion());
            return HelpIndexOutcome.SKIPPED_UP_TO_DATE;
        }
        return null;
    }

    /**
     * Why this AI configuration cannot back retrieval, or {@code null} when it can. Mirrors the
     * structural gate the admin write path applies, because a configuration can stop being retrievable
     * after it was validated — RAG turned off, the embedding provider swapped for Anthropic.
     */
    private HelpIndexError retrievalBlocker(AiConfigEntity aiConfig) {
        if (!aiConfig.isRagEnabled() || aiConfig.getRagStoreType() == null) {
            return HelpIndexError.of("error.help_agent.rag_not_enabled");
        }
        if (aiConfig.getEmbeddingProvider() == null) {
            return HelpIndexError.of("error.help_agent.embedding_provider_required");
        }
        if (aiConfig.getEmbeddingProvider() == AiProviderType.ANTHROPIC) {
            return HelpIndexError.of("error.help_agent.embedding_provider_invalid");
        }
        return null;
    }

    private HelpIndexOutcome ingest(HelpAgentConfigEntity row, AiConfigEntity aiConfig) {
        if (aiConfig.getRagStoreType() == RagStoreType.PGVECTOR
                && !pgVectorAvailability.isAvailable()) {
            log.warn("Skipping help corpus indexing for organization {}: the store is PGVECTOR but "
                    + "pgvector is {} on this deployment", row.getOrganizationId(),
                    pgVectorAvailability.status());
            return HelpIndexOutcome.SKIPPED_PGVECTOR_UNAVAILABLE;
        }
        EmbeddingModel embeddingModel;
        VectorStore vectorStore;
        try {
            embeddingModel = ragComponentsFactory.embeddingModel(aiConfig);
            vectorStore = ragComponentsFactory.vectorStore(aiConfig, embeddingModel);
        } catch (RuntimeException e) {
            return fail(row, HelpIndexError.of("error.help_agent.index.components_failed", reason(e)));
        }
        if (aiConfig.getRagStoreType() == RagStoreType.PGVECTOR) {
            var mismatch = probeDimension(embeddingModel);
            if (mismatch != null) {
                log.error("Help corpus indexing for organization {} aborted: {} {}",
                        row.getOrganizationId(), mismatch.messageKey(), mismatch.args());
                return fail(row, mismatch);
            }
        }
        // One snapshot for the whole pass. corpusVersion() and chunks() are separate reads, and an
        // optional remote refresh (AF-907) landing between them would stamp the new corpus's text
        // with the previous version — wrong until some later pass happened to re-index it.
        var corpus = bundle.snapshot();
        if (corpus == null) {
            return fail(row, HelpIndexError.of("error.help_agent.index.corpus_unavailable",
                    bundle.loadError()));
        }
        var corpusVersion = corpus.corpusVersion();
        try {
            vectorStore.delete(HelpCorpusMetadata.scopeFilter(row.getId()));
            var documents = toDocuments(row, corpus);
            for (int from = 0; from < documents.size(); from += properties.indexBatchSize()) {
                int to = Math.min(from + properties.indexBatchSize(), documents.size());
                vectorStore.add(documents.subList(from, to));
            }
            log.info("Indexed {} help documentation chunks (corpus {}) for organization {}",
                    documents.size(), corpusVersion, row.getOrganizationId());
        } catch (RuntimeException e) {
            log.error("Help corpus indexing failed for organization {}: {}", row.getOrganizationId(),
                    e.getMessage(), e);
            // Past the delete: the stored scope is now empty or half-written, so the version must go
            // with it. See failAfterStoreMutation.
            return failAfterStoreMutation(row, HelpIndexError.of("error.help_agent.index.failed",
                    reason(e)));
        }
        recordSuccess(row.getId(), corpusVersion);
        return HelpIndexOutcome.INDEXED;
    }

    /**
     * The one outbound call before ingestion. A dimension mismatch would otherwise surface as ~510
     * insert errors, and only for PGVECTOR — Qdrant sizes its collection from the first vector.
     */
    private HelpIndexError probeDimension(EmbeddingModel embeddingModel) {
        int actual;
        try {
            actual = embeddingModel.embed(PROBE_TEXT).length;
        } catch (RuntimeException e) {
            return HelpIndexError.of("error.help_agent.index.embedding_unreachable", reason(e));
        }
        int expected = ragComponentsFactory.pgvectorDimensions();
        if (actual != expected) {
            return HelpIndexError.of("error.help_agent.index.dimension_mismatch",
                    String.valueOf(actual), String.valueOf(expected));
        }
        return null;
    }

    private List<Document> toDocuments(HelpAgentConfigEntity row, HelpCorpusSnapshot corpus) {
        var documents = new ArrayList<Document>(corpus.chunks().size());
        for (var chunk : corpus.chunks()) {
            // The document id is left to the store: PgVectorStore's primary key is a UUID, and a
            // chunk id is a 16-character content hash. It travels as chunk_id metadata instead.
            documents.add(Document.builder()
                    .text(chunk.text())
                    .metadata(metadata(row, corpus.corpusVersion(), chunk))
                    .build());
        }
        return documents;
    }

    private static Map<String, Object> metadata(HelpAgentConfigEntity row, String corpusVersion,
                                                HelpCorpusChunk chunk) {
        // A LinkedHashMap, not Map.of: null values are rejected there, and a generated anchor or
        // section could be absent in a future bundle. nullToEmpty keeps the shape stable instead.
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put(HelpCorpusMetadata.KEY_CORPUS, HelpCorpusMetadata.CORPUS_HELP);
        metadata.put(HelpCorpusMetadata.KEY_HELP_CONFIG_ID, row.getId().toString());
        metadata.put(HelpCorpusMetadata.KEY_ORGANIZATION_ID, row.getOrganizationId().toString());
        metadata.put(HelpCorpusMetadata.KEY_CORPUS_VERSION, corpusVersion);
        metadata.put(HelpCorpusMetadata.KEY_CHUNK_ID, nullToEmpty(chunk.id()));
        metadata.put(HelpCorpusMetadata.KEY_TITLE, nullToEmpty(chunk.title()));
        metadata.put(HelpCorpusMetadata.KEY_SECTION, nullToEmpty(chunk.section()));
        metadata.put(HelpCorpusMetadata.KEY_ANCHOR, nullToEmpty(chunk.anchor()));
        metadata.put(HelpCorpusMetadata.KEY_URL, nullToEmpty(chunk.url()));
        return metadata;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Records a failure that happened <em>before</em> the store was touched, leaving
     * {@code indexed_corpus_version} alone — whatever is stored is still the intact corpus that
     * string names, and forgetting it would re-embed a perfectly good scope on the next pass.
     */
    private HelpIndexOutcome fail(HelpAgentConfigEntity row, HelpIndexError error) {
        log.warn("Help corpus indexing skipped for organization {}: {} {}", row.getOrganizationId(),
                error.messageKey(), error.args());
        stamp(row.getId(), stored -> stored.setIndexError(error.encode()));
        return HelpIndexOutcome.FAILED;
    }

    /**
     * Records a failure that happened <em>after</em> the delete, and clears
     * {@code indexed_corpus_version} with it.
     *
     * <p>This is the case the version compare cannot survive on its own. A forced pass — the admin
     * re-index button, or a changed embedding model — starts with the stored version already equal to
     * the bundle's. If a batch then fails, leaving the version in place would mark an emptied or
     * half-written scope as up to date, and every later unforced pass would skip it: the organization
     * would answer from nothing, permanently, until a human pressed re-index again. Clearing it makes
     * the next ordinary pass repair the row.
     */
    private HelpIndexOutcome failAfterStoreMutation(HelpAgentConfigEntity row, HelpIndexError error) {
        stamp(row.getId(), stored -> {
            stored.setIndexError(error.encode());
            stored.setIndexedCorpusVersion(null);
            stored.setIndexedAt(null);
        });
        return HelpIndexOutcome.FAILED;
    }

    /**
     * Stamps ingestion state by re-reading the row and merging, deliberately <em>outside</em> any
     * transaction of the indexing pass. Indexing takes minutes; holding a transaction across it would
     * pin a connection, and holding the entity would lose the write to an optimistic-lock failure the
     * moment an admin saved the settings page meanwhile. Re-reading also means the {@code @Version}
     * check is against the row as it is now, not as it was when the pass started.
     *
     * <p>A lost race here is logged, not thrown: the vectors are already stored, and the next pass
     * re-stamps. Propagating would turn a bookkeeping conflict into a failed index.
     */
    void recordSuccess(UUID helpAgentConfigId, String corpusVersion) {
        stamp(helpAgentConfigId, row -> {
            row.setIndexedCorpusVersion(corpusVersion);
            row.setIndexedAt(clock.instant());
            row.setIndexError(null);
        });
    }

    private void stamp(UUID helpAgentConfigId, Consumer<HelpAgentConfigEntity> mutation) {
        try {
            helpAgentConfigRepository.findById(helpAgentConfigId).ifPresent(row -> {
                mutation.accept(row);
                row.setUpdatedAt(clock.instant());
                helpAgentConfigRepository.save(row);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record help corpus ingestion state for help_agent_config={}: {}",
                    helpAgentConfigId, reason(e));
        }
    }

}
