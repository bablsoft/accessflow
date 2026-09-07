package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Indexes the corpus actually bundled in this build into a real {@code vector_store}, which is the
 * only way to prove the three things that matter and cannot be mocked: the rows land, the AND filter
 * the delete and the retriever rely on is understood by {@code PgVectorStore}, and a re-index replaces
 * rather than accumulates.
 *
 * <p>The embedding model is a counting deterministic stub, so "a second pass at the same corpus
 * version costs nothing" and "an install with the feature off embeds nothing" are assertions about a
 * measured call count rather than about intent. {@code RagComponentsFactory} is mocked to hand it over
 * — that is the seam between "which provider" (not under test) and "what gets stored" (the subject).
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpCorpusIndexerIntegrationTest {

    private static final int DIMENSIONS = 1536;

    @Autowired HelpCorpusIndexer indexer;
    @Autowired HelpCorpusBundle bundle;
    @Autowired HelpAgentConfigRepository repository;
    @Autowired AiConfigRepository aiConfigRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean RagComponentsFactory ragComponentsFactory;

    private final CountingEmbeddingModel embeddingModel = new CountingEmbeddingModel();

    private final List<UUID> createdHelpConfigIds = new ArrayList<>();

    private UUID organizationId;
    private HelpAgentConfigEntity helpConfig;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM vector_store");
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Help Corpus Org " + UUID.randomUUID());
        org.setSlug("help-corpus-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();

        var aiConfig = aiConfigRepository.save(aiConfig(organizationId));
        helpConfig = repository.save(row(organizationId, aiConfig.getId()));
        createdHelpConfigIds.add(helpConfig.getId());

        VectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .schemaName("public")
                .vectorTableName("vector_store")
                .build();
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(store);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(DIMENSIONS);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM vector_store");
        // By id, not by the entities held here: the indexer bumps @Version while stamping ingestion
        // state, so a delete of a pre-loaded instance would fail the optimistic-lock check.
        repository.deleteAllById(createdHelpConfigIds);
        createdHelpConfigIds.clear();
    }

    @Test
    void indexesTheWholeBundledCorpusWithTheExpectedMetadata() {
        assertThat(indexer.index(helpConfig.getId(), false)).isEqualTo(HelpIndexOutcome.INDEXED);

        assertThat(helpChunkCount()).isEqualTo(bundle.chunkCount());
        var sample = jdbcTemplate.queryForMap(
                "SELECT metadata::jsonb AS m FROM vector_store WHERE metadata::jsonb ->> 'corpus' = 'help' LIMIT 1");
        assertThat(sample.get("m").toString())
                .contains("\"corpus\": \"help\"")
                .contains("\"help_config_id\": \"" + helpConfig.getId() + "\"")
                .contains("\"organization_id\": \"" + organizationId + "\"")
                .contains("\"corpus_version\": \"" + bundle.corpusVersion() + "\"")
                .contains("\"chunk_id\"")
                .contains("\"section\"")
                .contains("\"url\"");
    }

    @Test
    void neverWritesAnAiConfigIdOntoAHelpChunk() {
        indexer.index(helpConfig.getId(), false);

        Integer leaked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata::jsonb ? 'corpus' "
                        + "AND metadata::jsonb ? 'ai_config_id'", Integer.class);

        // The one property that makes every existing customer's knowledge base invisible to help.
        assertThat(leaked).isZero();
    }

    @Test
    void stampsTheIngestedVersionOnTheRow() {
        indexer.index(helpConfig.getId(), false);

        var reloaded = repository.findById(helpConfig.getId()).orElseThrow();
        assertThat(reloaded.getIndexedCorpusVersion()).isEqualTo(bundle.corpusVersion());
        assertThat(reloaded.getIndexedAt()).isNotNull();
        assertThat(reloaded.getIndexError()).isNull();
    }

    @Test
    void aSecondPassAtTheSameCorpusVersionEmbedsNothing() {
        indexer.index(helpConfig.getId(), false);
        int afterFirst = embeddingModel.embedCalls.get();

        assertThat(indexer.index(helpConfig.getId(), false))
                .isEqualTo(HelpIndexOutcome.SKIPPED_UP_TO_DATE);
        assertThat(embeddingModel.embedCalls.get()).isEqualTo(afterFirst);
        assertThat(helpChunkCount()).isEqualTo(bundle.chunkCount());
    }

    @Test
    void aChangedCorpusVersionReplacesRatherThanDuplicates() {
        indexer.index(helpConfig.getId(), false);

        // What an upgrade looks like from the row's side: the bundle moved on, this org has not.
        var stale = repository.findById(helpConfig.getId()).orElseThrow();
        stale.setIndexedCorpusVersion("0000staleaaa");
        repository.save(stale);

        assertThat(indexer.index(helpConfig.getId(), false)).isEqualTo(HelpIndexOutcome.INDEXED);
        assertThat(helpChunkCount()).isEqualTo(bundle.chunkCount());
        Integer staleRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store "
                        + "WHERE metadata::jsonb ->> 'corpus_version' = '0000staleaaa'", Integer.class);
        assertThat(staleRows).isZero();
    }

    @Test
    void aForcedReindexReplacesTheSameVersionInPlace() {
        indexer.index(helpConfig.getId(), false);

        assertThat(indexer.index(helpConfig.getId(), true)).isEqualTo(HelpIndexOutcome.INDEXED);

        assertThat(helpChunkCount()).isEqualTo(bundle.chunkCount());
    }

    @Test
    void oneOrganizationsReindexLeavesAnotherOrganizationsChunksAlone() {
        indexer.index(helpConfig.getId(), false);

        var otherOrg = new OrganizationEntity();
        otherOrg.setId(UUID.randomUUID());
        otherOrg.setName("Other Org " + UUID.randomUUID());
        otherOrg.setSlug("other-org-" + UUID.randomUUID());
        organizationRepository.save(otherOrg);
        var otherAiConfig = aiConfigRepository.save(aiConfig(otherOrg.getId()));
        var otherHelp = repository.save(row(otherOrg.getId(), otherAiConfig.getId()));
        createdHelpConfigIds.add(otherHelp.getId());

        indexer.index(otherHelp.getId(), false);
        indexer.index(otherHelp.getId(), true);

        // The delete is scoped by help_config_id, so a per-tenant re-index is not a global truncate.
        assertThat(helpChunkCount(helpConfig.getId())).isEqualTo(bundle.chunkCount());
        assertThat(helpChunkCount(otherHelp.getId())).isEqualTo(bundle.chunkCount());
    }

    @Test
    void anOrganizationWithTheFeatureDisabledMakesNoEmbeddingCallAtAll() {
        helpConfig.setEnabled(false);
        repository.save(helpConfig);

        assertThat(indexer.index(helpConfig.getId(), false))
                .isEqualTo(HelpIndexOutcome.SKIPPED_DISABLED);

        // The whole cost of shipping a feature nobody turned on.
        assertThat(embeddingModel.embedCalls.get()).isZero();
        assertThat(helpChunkCount()).isZero();
        assertThat(repository.findById(helpConfig.getId()).orElseThrow().getIndexedCorpusVersion())
                .isNull();
    }

    @Test
    void theWorkListExcludesADisabledOrganization() {
        helpConfig.setEnabled(false);
        repository.save(helpConfig);

        // findAllByEnabledTrue() is the work list, so a disabled row is never even offered to a pass.
        // Other test classes leave enabled rows behind, hence an assertion scoped to this one.
        assertThat(indexer.enabledConfigIds()).doesNotContain(helpConfig.getId());
    }

    @Test
    void theIndexedCorpusIsRetrievableWithItsCitationMetadata() {
        indexer.index(helpConfig.getId(), false);
        var anyChunk = bundle.chunks().getFirst();
        var retriever = new HelpCorpusRetriever(
                ragComponentsFactory.vectorStore(null, embeddingModel), helpConfig.getId(), 3, 0.0);

        var hits = retriever.retrieve(anyChunk.text());

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunkId()).isEqualTo(anyChunk.id());
        assertThat(hits.getFirst().url()).isEqualTo(anyChunk.url());
        assertThat(hits.getFirst().title()).isEqualTo(anyChunk.title());
    }

    private Integer helpChunkCount() {
        return helpChunkCount(helpConfig.getId());
    }

    private Integer helpChunkCount(UUID helpConfigId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata::jsonb ->> 'corpus' = 'help' "
                        + "AND metadata::jsonb ->> 'help_config_id' = ?", Integer.class,
                helpConfigId.toString());
    }

    private static HelpAgentConfigEntity row(UUID organizationId, UUID aiConfigId) {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setAiConfigId(aiConfigId);
        entity.setEnabled(true);
        return entity;
    }

    private static AiConfigEntity aiConfig(UUID organizationId) {
        var config = new AiConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(organizationId);
        config.setName("help-corpus-" + UUID.randomUUID());
        config.setProvider(AiProviderType.OPENAI);
        config.setModel("gpt-4o");
        config.setRagEnabled(true);
        config.setRagStoreType(RagStoreType.PGVECTOR);
        config.setEmbeddingProvider(AiProviderType.OPENAI);
        config.setEmbeddingModel("text-embedding-3-small");
        return config;
    }

    /** Deterministic one-hot embedding that counts how many texts it was asked to embed. */
    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private final AtomicInteger embedCalls = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = new ArrayList<Embedding>();
            var texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                embedCalls.incrementAndGet();
                embeddings.add(new Embedding(vectorFor(texts.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            embedCalls.incrementAndGet();
            return vectorFor(document.getText());
        }

        @Override
        public float[] embed(String text) {
            embedCalls.incrementAndGet();
            return vectorFor(text);
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private static float[] vectorFor(String text) {
            var vector = new float[DIMENSIONS];
            vector[Math.floorMod(text.hashCode(), DIMENSIONS)] = 1.0f;
            return vector;
        }
    }
}
