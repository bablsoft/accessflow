package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.engine.databricks.DatabricksResultReader.Chunk;
import com.bablsoft.accessflow.engine.databricks.DatabricksResultReader.ExternalLink;
import com.bablsoft.accessflow.engine.databricks.DatabricksStatementClient.Truncation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the materialization loop with lambda collaborators — no HTTP — so the row cap, the byte
 * backstop and the two chunk shapes are asserted directly rather than inferred from a stub server.
 */
class DatabricksResultReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BIG_BUDGET = 1_048_576L;

    private final List<Integer> fetchedChunks = new ArrayList<>();
    private final List<Integer> readLinks = new ArrayList<>();

    @Test
    void readsColumnsAndInlineRows() {
        var result = read(json("""
                {"manifest":{"schema":{"columns":[
                    {"name":"id","type_name":"BIGINT"},{"name":"name","type_name":"STRING"}]}},
                 "result":{"data_array":[["1","Ada"],["2",null]]}}"""), null, Map.of(), Map.of());

        assertThat(result.columns()).containsExactly(
                new DatabricksStatementClient.Column("id", "BIGINT"),
                new DatabricksStatementClient.Column("name", "STRING"));
        assertThat(result.rows()).containsExactly(List.of("1", "Ada"), rowWithNull("2"));
        assertThat(result.truncation()).isEqualTo(Truncation.NONE);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void followsInlineChunkLinks() {
        var result = read(json("""
                {"manifest":{},"result":{"data_array":[["1"]],"next_chunk_index":1}}"""), null,
                Map.of(1, """
                        {"data_array":[["2"]],"next_chunk_index":2}""",
                        2, """
                        {"data_array":[["3"]]}"""), Map.of());

        assertThat(result.rows()).containsExactly(List.of("1"), List.of("2"), List.of("3"));
        assertThat(fetchedChunks).containsExactly(1, 2);
    }

    @Test
    void stopsAtTheRowCapWithoutFetchingTheNextInlineChunk() {
        var result = read(json("""
                {"manifest":{},"result":{"data_array":[["1"],["2"],["3"]],"next_chunk_index":1}}"""),
                2, Map.of(1, """
                        {"data_array":[["4"]]}"""), Map.of());

        assertThat(result.rows()).containsExactly(List.of("1"), List.of("2"));
        assertThat(result.truncation()).isEqualTo(Truncation.ROW_LIMIT);
        assertThat(fetchedChunks).isEmpty();
    }

    @Test
    void manifestTruncationFlagBecomesARowLimitCut() {
        var result = read(json("""
                {"manifest":{"truncated":true},"result":{"data_array":[["1"]]}}"""), null,
                Map.of(), Map.of());

        assertThat(result.truncation()).isEqualTo(Truncation.ROW_LIMIT);
    }

    @Test
    void readsEveryExternalLinkInAChunk() {
        var result = read(json("""
                {"manifest":{},"result":{"external_links":[
                    {"chunk_index":0,"row_count":1,"byte_count":10,"external_link":"https://s/0"},
                    {"chunk_index":1,"row_count":1,"byte_count":10,"external_link":"https://s/1"}]}}"""),
                null, Map.of(), Map.of(0, chunk(20, List.of("a")), 1, chunk(20, List.of("b"))));

        assertThat(result.rows()).containsExactly(List.of("a"), List.of("b"));
        assertThat(readLinks).containsExactly(0, 1);
        assertThat(result.truncation()).isEqualTo(Truncation.NONE);
    }

    @Test
    void followsTheNextChunkIndexCarriedOnTheLastExternalLink() {
        var result = read(json("""
                {"manifest":{},"result":{"external_links":[
                    {"chunk_index":0,"byte_count":10,"external_link":"https://s/0",
                     "next_chunk_index":1}]}}"""),
                null, Map.of(1, """
                        {"external_links":[{"chunk_index":1,"byte_count":10,
                          "external_link":"https://s/1"}]}"""),
                Map.of(0, chunk(10, List.of("a")), 1, chunk(10, List.of("b"))));

        assertThat(result.rows()).containsExactly(List.of("a"), List.of("b"));
        assertThat(fetchedChunks).containsExactly(1);
        assertThat(readLinks).containsExactly(0, 1);
    }

    @Test
    void stopsAtTheRowCapWithoutReadingTheSecondLink() {
        var result = read(json("""
                {"manifest":{},"result":{"external_links":[
                    {"chunk_index":0,"byte_count":10,"external_link":"https://s/0"},
                    {"chunk_index":1,"byte_count":10,"external_link":"https://s/1"}]}}"""),
                1, Map.of(), Map.of(0, chunk(10, List.of("a")), 1, chunk(10, List.of("b"))));

        assertThat(result.rows()).containsExactly(List.of("a"));
        assertThat(result.truncation()).isEqualTo(Truncation.ROW_LIMIT);
        assertThat(readLinks).containsExactly(0);
    }

    @Test
    void skipsALinkWhoseAdvertisedSizeAloneBlowsTheBudget() {
        var reader = new DatabricksResultReader(100L);
        var result = reader.read(json("""
                {"manifest":{},"result":{"external_links":[
                    {"chunk_index":0,"byte_count":5000,"external_link":"https://s/0"}]}}"""),
                null, this::fetch, this::read);

        assertThat(result.rows()).isEmpty();
        assertThat(result.truncation()).isEqualTo(Truncation.BYTE_LIMIT);
        assertThat(readLinks).isEmpty();
    }

    @Test
    void stopsWhenALinkReportsItOverranTheBudget() {
        var reader = new DatabricksResultReader(100L);
        var result = reader.read(json("""
                {"manifest":{},"result":{"external_links":[
                    {"chunk_index":0,"byte_count":10,"external_link":"https://s/0"},
                    {"chunk_index":1,"byte_count":10,"external_link":"https://s/1"}]}}"""),
                null, this::fetch,
                (link, budget) -> {
                    readLinks.add(link.chunkIndex());
                    return new Chunk(List.of(List.of("a")), 101L, true);
                });

        assertThat(result.rows()).containsExactly(List.of("a"));
        assertThat(result.truncation()).isEqualTo(Truncation.BYTE_LIMIT);
        assertThat(readLinks).containsExactly(0);
    }

    @Test
    void stopsWhenTheCumulativeByteTotalPassesTheBackstop() {
        var reader = new DatabricksResultReader(100L);
        var result = reader.read(json("""
                {"manifest":{},"result":{"external_links":[
                    {"chunk_index":0,"byte_count":60,"external_link":"https://s/0"},
                    {"chunk_index":1,"byte_count":60,"external_link":"https://s/1"}]}}"""),
                null, this::fetch,
                (link, budget) -> {
                    readLinks.add(link.chunkIndex());
                    return new Chunk(List.of(List.of("row" + link.chunkIndex())), 60L, false);
                });

        assertThat(result.rows()).containsExactly(List.of("row0"));
        assertThat(result.truncation()).isEqualTo(Truncation.BYTE_LIMIT);
        assertThat(readLinks).containsExactly(0);
    }

    @Test
    void anEmptyExternalLinkArrayYieldsNoRows() {
        var result = read(json("""
                {"manifest":{"schema":{"columns":[{"name":"id","type_name":"INT"}]}},
                 "result":{"external_links":[]}}"""), null, Map.of(), Map.of());

        assertThat(result.rows()).isEmpty();
        assertThat(result.columns()).hasSize(1);
        assertThat(result.truncation()).isEqualTo(Truncation.NONE);
    }

    @Test
    void aLinkWithoutAUrlIsIgnored() {
        var result = read(json("""
                {"manifest":{},"result":{"external_links":[{"chunk_index":0,"external_link":""}]}}"""),
                null, Map.of(), Map.of());

        assertThat(result.rows()).isEmpty();
        assertThat(readLinks).isEmpty();
    }

    @Test
    void aMissingResultNodeYieldsAnEmptyResult() {
        var result = read(json("""
                {"manifest":{"schema":{"columns":[{"name":"id","type_name":"INT"}]}}}"""), null,
                Map.of(), Map.of());

        assertThat(result.rows()).isEmpty();
        assertThat(result.truncation()).isEqualTo(Truncation.NONE);
    }

    @Test
    void aChunkFetchFailurePropagates() {
        var reader = new DatabricksResultReader(BIG_BUDGET);
        assertThatThrownBy(() -> reader.read(json("""
                {"manifest":{},"result":{"data_array":[["1"]],"next_chunk_index":1}}"""), null,
                chunkIndex -> {
                    throw new DatabricksApiException("chunk gone", null, 404, false);
                }, this::read))
                .isInstanceOf(DatabricksApiException.class)
                .hasMessage("chunk gone");
    }

    @Test
    void externalLinkToStringRedactsTheUrl() {
        var link = new ExternalLink(3, 10, 20, "https://bucket.example/presigned?sig=secret");
        assertThat(link.toString()).doesNotContain("secret").contains("chunkIndex=3", "<redacted>");
    }

    // ---- harness --------------------------------------------------------------------------------

    private Map<Integer, String> chunkBodies = Map.of();
    private Map<Integer, Chunk> linkBodies = Map.of();

    private DatabricksStatementClient.StatementResult read(JsonNode response, Integer rowLimit,
                                                           Map<Integer, String> chunks,
                                                           Map<Integer, Chunk> links) {
        this.chunkBodies = chunks;
        this.linkBodies = links;
        return new DatabricksResultReader(BIG_BUDGET).read(response, rowLimit, this::fetch,
                this::read);
    }

    private JsonNode fetch(int chunkIndex) {
        fetchedChunks.add(chunkIndex);
        var body = chunkBodies.get(chunkIndex);
        if (body == null) {
            throw new AssertionError("unexpected chunk fetch " + chunkIndex);
        }
        return json(body);
    }

    private Chunk read(ExternalLink link, long byteBudget) {
        readLinks.add(link.chunkIndex());
        var chunk = linkBodies.get(link.chunkIndex());
        if (chunk == null) {
            throw new AssertionError("unexpected link read " + link.chunkIndex());
        }
        return chunk;
    }

    private static Chunk chunk(long bytes, List<String> singleColumnValues) {
        var rows = new ArrayList<List<String>>();
        for (var value : singleColumnValues) {
            rows.add(List.of(value));
        }
        return new Chunk(rows, bytes, false);
    }

    private static List<String> rowWithNull(String first) {
        var row = new ArrayList<String>();
        row.add(first);
        row.add(null);
        return row;
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
