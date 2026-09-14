package com.bablsoft.accessflow.sqlreview.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewFindingArgsCodecTest {

    private final SqlReviewFindingArgsCodec codec = new SqlReviewFindingArgsCodec(new ObjectMapper());

    @Test
    void roundTripsAFlatStringObject() {
        var args = new LinkedHashMap<String, String>();
        args.put("table", "payroll.salaries");
        args.put("glob", "payroll.*");

        var json = codec.encode(args);

        assertThat(json).isEqualTo("{\"table\":\"payroll.salaries\",\"glob\":\"payroll.*\"}");
        assertThat(codec.decode(json)).containsExactlyInAnyOrderEntriesOf(args);
    }

    @Test
    void emptyArgsEncodeAsNullSoTheColumnStaysNull() {
        assertThat(codec.encode(Map.of())).isNull();
        assertThat(codec.encode(null)).isNull();
    }

    @Test
    void nullAndBlankDecodeAsNoArgs() {
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("  ")).isEmpty();
        assertThat(codec.decode("null")).isEmpty();
    }

    @Test
    void anUnreadableOrNonObjectBlobDecodesAsNoArgsRatherThanFailingARead() {
        assertThat(codec.decode("{not json")).isEmpty();
        assertThat(codec.decode("[\"a\"]")).isEmpty();
        assertThat(codec.decode("\"scalar\"")).isEmpty();
    }

    @Test
    void nonStringValuesAreCoercedAndNullsBecomeEmpty() {
        var decoded = codec.decode("{\"count\": 3, \"name\": null}");

        assertThat(decoded).containsEntry("count", "3").containsEntry("name", "");
    }
}
