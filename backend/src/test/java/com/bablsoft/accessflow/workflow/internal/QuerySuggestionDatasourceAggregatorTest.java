package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusLookupService;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusRow;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlCanonicalizer;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySuggestionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuerySuggestionDatasourceAggregatorTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    /**
     * Faithful stand-in for {@code core.internal.DefaultSqlCanonicalizer}, which is package-private
     * and has its own test. Grouping is what is under test here, so a mock returning a fixed key
     * would assert nothing.
     */
    private static final SqlCanonicalizer CANONICALIZER = sql -> {
        if (sql == null) {
            return null;
        }
        var stripped = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
        var collapsed = stripped.replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed.toUpperCase(java.util.Locale.ROOT);
    };

    private QuerySuggestionCorpusLookupService corpusLookupService;
    private QueryParser queryParser;
    private QuerySuggestionRepository suggestionRepository;
    private QuerySuggestionDatasourceAggregator aggregator;

    @BeforeEach
    void setUp() {
        corpusLookupService = mock(QuerySuggestionCorpusLookupService.class);
        queryParser = mock(QueryParser.class);
        suggestionRepository = mock(QuerySuggestionRepository.class);
        when(suggestionRepository.findByDatasourceIdAndCanonicalHash(any(), anyString()))
                .thenReturn(Optional.empty());
        aggregator = newAggregator(1, 50, 50, 4000);
    }

    private QuerySuggestionDatasourceAggregator newAggregator(int minApprovedCount, int maxKept,
                                                              int maxTrackedSubmitters,
                                                              int maxSqlLength) {
        var properties = new QuerySuggestionProperties(true, null, Duration.ofDays(90), 2000,
                maxKept, maxTrackedSubmitters, minApprovedCount, maxSqlLength, null, 1, 1, 1, 10,
                50, null);
        return new QuerySuggestionDatasourceAggregator(corpusLookupService, CANONICALIZER,
                queryParser, suggestionRepository, properties);
    }

    @Test
    void cosmeticallyDifferentRunsOfTheSameQueryFormOneSuggestion() {
        givenCorpus(
                corpusRow("SELECT id  FROM orders -- nightly", ALICE, NOW),
                corpusRow("select id from orders", BOB, NOW.minus(Duration.ofDays(2))),
                corpusRow("select    ID from ORDERS", ALICE, NOW.minus(Duration.ofDays(4))));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        var saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getApprovedCount()).isEqualTo(3);
        assertThat(saved.getFirst().getDistinctSubmitterCount()).isEqualTo(2);
        // The representative is the newest row's RAW text — never the upper-cased canonical form.
        assertThat(saved.getFirst().getSqlText()).isEqualTo("SELECT id  FROM orders -- nightly");
        assertThat(saved.getFirst().getLastSubmittedAt()).isEqualTo(NOW);
        assertThat(saved.getFirst().getFirstSubmittedAt())
                .isEqualTo(NOW.minus(Duration.ofDays(4)));
    }

    @Test
    void aQueryShapeIsParsedOncePerGroupNotOncePerRow() {
        givenCorpus(corpusRow("select id from orders", ALICE, NOW),
                corpusRow("select id from orders", BOB, NOW.minusSeconds(60)),
                corpusRow("select id from orders", ALICE, NOW.minusSeconds(120)),
                corpusRow("select id from refunds", BOB, NOW.minusSeconds(180)),
                corpusRow("select id from refunds", BOB, NOW.minusSeconds(240)));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(queryParser, times(2)).parse(anyString(), any());
    }

    @Test
    void anUnparseableShapeIsSkippedAndNeverReparsed() {
        givenCorpus(corpusRow("!! not sql !!", ALICE, NOW),
                corpusRow("!! not sql !!", BOB, NOW.minusSeconds(60)),
                corpusRow("!! not sql !!", ALICE, NOW.minusSeconds(120)));
        when(queryParser.parse(anyString(), any()))
                .thenThrow(new InvalidSqlException("unparseable"));

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(queryParser, times(1)).parse(anyString(), any());
        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void aShapeWithNoDetectedTablesIsDroppedRatherThanStored() {
        // An empty referencedTables set would clear every viewer's allow-list unconditionally.
        givenCorpus(corpusRow("select 1", ALICE, NOW), corpusRow("select 1", BOB, NOW));
        when(queryParser.parse(anyString(), any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("select 1"),
                        Set.of()));

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void shapesBelowTheMinimumApprovedCountAreNotOffered() {
        aggregator = newAggregator(2, 50, 50, 4000);
        givenCorpus(corpusRow("select id from orders", ALICE, NOW),
                corpusRow("select id from refunds", BOB, NOW),
                corpusRow("select id from refunds", ALICE, NOW.minusSeconds(60)));
        givenParse("refunds");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        assertThat(captureSaved()).extracting(QuerySuggestionEntity::getApprovedCount)
                .containsExactly(2);
    }

    @Test
    void overlongSqlIsSkippedEntirely() {
        aggregator = newAggregator(1, 50, 50, 10);
        givenCorpus(corpusRow("select id from a_very_long_table_name", ALICE, NOW));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(queryParser, never()).parse(anyString(), any());
        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void blankAndCommentOnlySqlIsSkipped() {
        givenCorpus(corpusRow("   ", ALICE, NOW), corpusRow("-- just a note", BOB, NOW));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void onlyTheStrongestShapesAreKeptPerDatasource() {
        aggregator = newAggregator(1, 2, 50, 4000);
        givenCorpus(corpusRow("select 1 from a", ALICE, NOW),
                corpusRow("select 1 from a", BOB, NOW),
                corpusRow("select 1 from a", ALICE, NOW),
                corpusRow("select 2 from b", BOB, NOW),
                corpusRow("select 2 from b", ALICE, NOW),
                corpusRow("select 3 from c", BOB, NOW));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        assertThat(captureSaved()).extracting(QuerySuggestionEntity::getApprovedCount)
                .containsExactly(3, 2);
    }

    @Test
    void trackedSubmittersAreCappedButTheBreadthCountStaysTruthful() {
        aggregator = newAggregator(1, 50, 2, 4000);
        var carol = UUID.randomUUID();
        givenCorpus(corpusRow("select id from orders", ALICE, NOW),
                corpusRow("select id from orders", BOB, NOW.minusSeconds(60)),
                corpusRow("select id from orders", carol, NOW.minusSeconds(120)),
                // Alice again, after the cap was reached — must not be counted twice.
                corpusRow("select id from orders", ALICE, NOW.minusSeconds(180)));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        var saved = captureSaved().getFirst();
        assertThat(saved.getSubmitterIds()).containsExactly(ALICE, BOB);
        assertThat(saved.getDistinctSubmitterCount()).isEqualTo(3);
        assertThat(saved.getApprovedCount()).isEqualTo(4);
    }

    @Test
    void anExistingRowIsUpdatedInPlaceSoItsCreationTimeSurvives() {
        var existing = new QuerySuggestionEntity();
        existing.setId(UUID.randomUUID());
        existing.setCreatedAt(NOW.minus(Duration.ofDays(200)));
        when(suggestionRepository.findByDatasourceIdAndCanonicalHash(any(), anyString()))
                .thenReturn(Optional.of(existing));
        givenCorpus(corpusRow("select id from orders", ALICE, NOW));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        var saved = captureSaved().getFirst();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW.minus(Duration.ofDays(200)));
        assertThat(saved.getComputedAt()).isEqualTo(NOW);
    }

    @Test
    void rowsThePassDidNotRewriteAreSweptForThisDatasourceOnly() {
        givenCorpus(corpusRow("select id from orders", ALICE, NOW));
        givenParse("orders");

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(suggestionRepository).deleteStaleForDatasource(DATASOURCE, NOW);
    }

    @Test
    void theLookbackWindowIsMeasuredBackFromTheRunStamp() {
        givenCorpus();

        aggregator.aggregate(ORG, DATASOURCE, NOW);

        verify(corpusLookupService).findCorpus(eq(DATASOURCE),
                eq(NOW.minus(Duration.ofDays(90))), anyInt());
    }

    private void givenCorpus(QuerySuggestionCorpusRow... rows) {
        when(corpusLookupService.findCorpus(eq(DATASOURCE), any(), anyInt()))
                .thenReturn(List.of(rows));
    }

    private void givenParse(String table) {
        when(queryParser.parse(anyString(), any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("sql"),
                        Set.of(table)));
    }

    private List<QuerySuggestionEntity> captureSaved() {
        var captor = ArgumentCaptor.forClass(QuerySuggestionEntity.class);
        verify(suggestionRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private static QuerySuggestionCorpusRow corpusRow(String sql, UUID submitter, Instant at) {
        return new QuerySuggestionCorpusRow(DATASOURCE, DbType.POSTGRESQL, sql, QueryType.SELECT,
                submitter, at);
    }
}
