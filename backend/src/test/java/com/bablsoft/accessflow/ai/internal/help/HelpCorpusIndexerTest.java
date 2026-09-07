package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.PgVectorStatus;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpCorpusIndexerTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private static final String CORPUS_VERSION = "b3afb123fe5b";
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();

    @Mock HelpCorpusBundle bundle;
    @Mock HelpAgentConfigRepository repository;
    @Mock AiConfigRepository aiConfigRepository;
    @Mock RagComponentsFactory ragComponentsFactory;
    @Mock PgVectorAvailability pgVectorAvailability;
    @Mock EmbeddingModel embeddingModel;
    @Mock VectorStore vectorStore;

    private HelpCorpusIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new HelpCorpusIndexer(bundle, repository, aiConfigRepository, ragComponentsFactory,
                pgVectorAvailability, new HelpAgentProperties(true, 2, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(bundle.available()).thenReturn(true);
        lenient().when(bundle.corpusVersion()).thenReturn(CORPUS_VERSION);
        lenient().when(bundle.chunkCount()).thenReturn(3);
        lenient().when(bundle.chunks()).thenReturn(chunks(3));
    }

    // --- skips: the paths that must cost nothing ------------------------------------------------

    @Test
    void skipsARowWhoseAgentIsSwitchedOff() {
        var row = row();
        row.setEnabled(false);
        stored(row);

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.SKIPPED_DISABLED);
        verifyNoEmbedding();
    }

    @Test
    void skipsARowWhoseBoundConfigurationWasDeleted() {
        var row = row();
        row.setAiConfigId(null);
        stored(row);

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.SKIPPED_UNBOUND);
        verifyNoEmbedding();
    }

    @Test
    void skipsARowWithRetrievalTurnedOff() {
        var row = row();
        row.setRetrievalEnabled(false);
        stored(row);

        assertThat(indexer.index(row.getId(), false))
                .isEqualTo(HelpIndexOutcome.SKIPPED_RETRIEVAL_OFF);
        verifyNoEmbedding();
    }

    @Test
    void skipsARowThatAlreadyHoldsThisCorpusVersion() {
        var row = row();
        row.setIndexedCorpusVersion(CORPUS_VERSION);
        stored(row);

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.SKIPPED_UP_TO_DATE);
        verifyNoEmbedding();
    }

    @Test
    void forcingIgnoresTheVersionCompare() {
        var row = row();
        row.setIndexedCorpusVersion(CORPUS_VERSION);
        stored(row);
        bindRetrievableConfig();

        assertThat(indexer.index(row.getId(), true)).isEqualTo(HelpIndexOutcome.INDEXED);
        verify(vectorStore).delete(any(Filter.Expression.class));
    }

    @Test
    void skipsAConfigurationWhosePgVectorStoreIsUnavailable() {
        var row = row();
        stored(row);
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(false);
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.EXTENSION_MISSING);

        assertThat(indexer.index(row.getId(), false))
                .isEqualTo(HelpIndexOutcome.SKIPPED_PGVECTOR_UNAVAILABLE);
        verifyNoEmbedding();
        // Not a failure the admin caused, so nothing is written to index_error.
        verify(repository, never()).save(any());
    }

    @Test
    void skipsARowThatVanishedBetweenTheWorkListAndThePass() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        // Not SKIPPED_DISABLED: nothing was switched off, the row is gone.
        assertThat(indexer.index(id, false)).isEqualTo(HelpIndexOutcome.SKIPPED_MISSING);
        verifyNoEmbedding();
    }

    // --- failures: recorded, never thrown, never disabling the agent ----------------------------

    @Test
    void recordsAMissingCorpusBundleAsAnIndexError() {
        var row = row();
        stored(row);
        when(bundle.available()).thenReturn(false);
        when(bundle.loadError()).thenReturn("corpus.jsonl is empty");

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        assertThat(savedRow().getIndexError())
                .startsWith("error.help_agent.index.corpus_unavailable")
                .contains("corpus.jsonl is empty");
        assertThat(savedRow().isEnabled()).isTrue();
        assertThat(savedRow().getIndexedCorpusVersion()).isNull();
    }

    @Test
    void recordsAConfigurationThatCanNoLongerEmbed() {
        var row = row();
        stored(row);
        bindConfig(aiConfig(true, RagStoreType.QDRANT, AiProviderType.ANTHROPIC));

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        assertThat(savedRow().getIndexError()).isEqualTo("error.help_agent.embedding_provider_invalid");
        verifyNoEmbedding();
    }

    @Test
    void recordsAConfigurationWhoseRagWasTurnedOffAfterEnabling() {
        var row = row();
        stored(row);
        bindConfig(aiConfig(false, RagStoreType.PGVECTOR, AiProviderType.OPENAI));

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        assertThat(savedRow().getIndexError()).isEqualTo("error.help_agent.rag_not_enabled");
    }

    @Test
    void recordsADeletedAiConfiguration() {
        var row = row();
        stored(row);
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        assertThat(savedRow().getIndexError()).isEqualTo("error.help_agent.index.ai_config_deleted");
    }

    @Test
    void recordsADimensionMismatchBeforeEmbeddingAnything() {
        var row = row();
        stored(row);
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(true);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[768]);

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        // The numbers are arguments, not prose: the sentence around them is translated at read time.
        assertThat(savedRow().getIndexError())
                .startsWith("error.help_agent.index.dimension_mismatch")
                .contains("768", "1536");
        // The whole point of probing: nothing is deleted before we know the vectors will fit.
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(any());
    }

    @Test
    void recordsAnUnreachableEmbeddingProvider() {
        var row = row();
        stored(row);
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(true);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(embeddingModel.embed(any(String.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        assertThat(savedRow().getIndexError())
                .startsWith("error.help_agent.index.embedding_unreachable")
                .contains("connection refused");
    }

    @Test
    void recordsAStoreFailureAndClearsTheStoredVersionBecauseTheScopeWasAlreadyDeleted() {
        var row = row();
        row.setIndexedCorpusVersion("0000deadbeef");
        stored(row);
        bindRetrievableConfig();
        org.mockito.Mockito.doThrow(new IllegalStateException("store down"))
                .when(vectorStore).add(any());

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);
        assertThat(savedRow().getIndexError())
                .startsWith("error.help_agent.index.failed")
                .contains("store down");
        // The delete already ran, so the scope is empty or half-written. Keeping a version here would
        // claim it is up to date.
        assertThat(savedRow().getIndexedCorpusVersion()).isNull();
        assertThat(savedRow().getIndexedAt()).isNull();
    }

    @Test
    void aFailedForcedReindexDoesNotLeaveTheRowClaimingToBeUpToDate() {
        var row = row();
        // The state a forced pass starts from: already at the bundle's version.
        row.setIndexedCorpusVersion(CORPUS_VERSION);
        row.setIndexedAt(NOW);
        stored(row);
        bindRetrievableConfig();
        org.mockito.Mockito.doThrow(new IllegalStateException("429 rate limited"))
                .when(vectorStore).add(any());

        assertThat(indexer.index(row.getId(), true)).isEqualTo(HelpIndexOutcome.FAILED);

        // Without clearing it, every later unforced pass would take SKIPPED_UP_TO_DATE over an empty
        // scope and the organization would answer from nothing until a human pressed re-index again.
        assertThat(savedRow().getIndexedCorpusVersion()).isNull();
    }

    @Test
    void aFailureBeforeTheDeleteKeepsTheStoredVersion() {
        var row = row();
        row.setIndexedCorpusVersion("0000deadbeef");
        stored(row);
        bindConfig(aiConfig(false, RagStoreType.PGVECTOR, AiProviderType.OPENAI));

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.FAILED);

        // Nothing touched the store, so what is in it is still the corpus that string names.
        assertThat(savedRow().getIndexedCorpusVersion()).isEqualTo("0000deadbeef");
    }

    // --- the happy path ------------------------------------------------------------------------

    @Test
    void deletesTheExistingScopeBeforeAddingTheNewCorpus() {
        var row = row();
        stored(row);
        bindRetrievableConfig();

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.INDEXED);

        var inOrder = org.mockito.Mockito.inOrder(vectorStore);
        inOrder.verify(vectorStore).delete(any(Filter.Expression.class));
        inOrder.verify(vectorStore, times(2)).add(any());
    }

    @Test
    void addsInBatchesOfTheConfiguredSize() {
        var row = row();
        stored(row);
        bindRetrievableConfig();

        indexer.index(row.getId(), false);

        // Batch size 2 over 3 chunks: two calls, of 2 and 1.
        var batches = addedBatches();
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(2);
        assertThat(batches.get(1)).hasSize(1);
    }

    @Test
    void tagsEveryChunkWithTheHelpScopeAndNeverWithAnAiConfigId() {
        var row = row();
        stored(row);
        bindRetrievableConfig();

        indexer.index(row.getId(), false);

        var first = addedBatches().getFirst().getFirst();
        assertThat(first.getText()).isEqualTo("chunk 0 text");
        assertThat(first.getMetadata())
                .containsEntry("corpus", "help")
                .containsEntry("help_config_id", row.getId().toString())
                .containsEntry("organization_id", ORG_ID.toString())
                .containsEntry("corpus_version", CORPUS_VERSION)
                .containsEntry("chunk_id", "chunk-0")
                .containsEntry("title", "Title 0")
                .containsEntry("section", "Guides")
                .containsEntry("anchor", "anchor-0")
                .containsEntry("url", "https://accessflow.io/docs/#anchor-0");
        // The isolation guarantee, asserted where it is produced rather than only where it is read.
        assertThat(first.getMetadata()).doesNotContainKey("ai_config_id");
    }

    @Test
    void stampsTheIngestionStateAndClearsAnyPreviousError() {
        var row = row();
        row.setIndexError("a previous failure");
        stored(row);
        bindRetrievableConfig();

        indexer.index(row.getId(), false);

        var saved = savedRow();
        assertThat(saved.getIndexedCorpusVersion()).isEqualTo(CORPUS_VERSION);
        assertThat(saved.getIndexedAt()).isEqualTo(NOW);
        assertThat(saved.getIndexError()).isNull();
    }

    @Test
    void aQdrantStoreIsNotDimensionProbed() {
        var row = row();
        stored(row);
        bindConfig(aiConfig(true, RagStoreType.QDRANT, AiProviderType.OPENAI));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);

        assertThat(indexer.index(row.getId(), false)).isEqualTo(HelpIndexOutcome.INDEXED);
        // Qdrant sizes its collection from the first vector, so there is nothing to check against.
        verify(embeddingModel, never()).embed(any(String.class));
        verify(pgVectorAvailability, never()).isAvailable();
    }

    @Test
    void truncatesARuinouslyLongProviderError() {
        var row = row();
        stored(row);
        bindRetrievableConfig();
        org.mockito.Mockito.doThrow(new IllegalStateException("x".repeat(5000)))
                .when(vectorStore).add(any());

        indexer.index(row.getId(), false);

        // The key, a separator, and 1000 characters of the provider's own words - not 5000.
        assertThat(savedRow().getIndexError())
                .hasSize("error.help_agent.index.failed".length() + 1 + 1000);
    }

    // --- the work list --------------------------------------------------------------------------

    @Test
    void theWorkListIsTheIdsOfEveryEnabledOrganization() {
        var first = row();
        var second = row();
        when(repository.findAllByEnabledTrue()).thenReturn(List.of(first, second));

        assertThat(indexer.enabledConfigIds()).containsExactly(first.getId(), second.getId());
    }

    @Test
    void theWorkListIsEmptyOnAnInstallWithTheFeatureOff() {
        when(repository.findAllByEnabledTrue()).thenReturn(List.of());

        assertThat(indexer.enabledConfigIds()).isEmpty();
        verifyNoEmbedding();
    }

    // --- helpers --------------------------------------------------------------------------------

    private void verifyNoEmbedding() {
        verify(ragComponentsFactory, never()).embeddingModel(any());
        verify(ragComponentsFactory, never()).vectorStore(any(), any());
    }

    private void stored(HelpAgentConfigEntity row) {
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
    }

    private void bindConfig(AiConfigEntity config) {
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(config));
    }

    private void bindRetrievableConfig() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(true);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1536]);
    }

    private HelpAgentConfigEntity savedRow() {
        var captor = ArgumentCaptor.forClass(HelpAgentConfigEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<List<Document>> addedBatches() {
        var captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).add(captor.capture());
        return (List<List<Document>>) (List<?>) captor.getAllValues();
    }

    private static HelpAgentConfigEntity row() {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setAiConfigId(AI_CONFIG_ID);
        entity.setEnabled(true);
        return entity;
    }

    private static AiConfigEntity aiConfig(boolean ragEnabled, RagStoreType storeType,
                                           AiProviderType embeddingProvider) {
        var config = new AiConfigEntity();
        config.setId(AI_CONFIG_ID);
        config.setOrganizationId(ORG_ID);
        config.setRagEnabled(ragEnabled);
        config.setRagStoreType(storeType);
        config.setEmbeddingProvider(embeddingProvider);
        return config;
    }

    private static List<HelpCorpusChunk> chunks(int count) {
        var chunks = new ArrayList<HelpCorpusChunk>();
        for (int i = 0; i < count; i++) {
            chunks.add(new HelpCorpusChunk("chunk-" + i, "website/docs/index.html",
                    "https://accessflow.io/docs/#anchor-" + i, "anchor-" + i, "Title " + i, "Guides",
                    0, 10, "chunk " + i + " text"));
        }
        return chunks;
    }
}
