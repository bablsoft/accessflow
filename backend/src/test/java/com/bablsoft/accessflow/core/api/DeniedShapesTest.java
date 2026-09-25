package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeniedShapesTest {

    private static SqlParseResult parsed(QueryType type, Set<QueryShape> shapes, boolean analyzed) {
        return new SqlParseResult(type, false, List.of("sql"), Set.of(), false, false, Set.of(),
                true, shapes, analyzed);
    }

    @Test
    void normalizeDropsNullsAndDuplicatesInDeclarationOrder() {
        var input = new ArrayList<QueryShape>();
        input.add(QueryShape.WINDOW_FUNCTION);
        input.add(null);
        input.add(QueryShape.JOIN);
        input.add(QueryShape.JOIN);

        assertThat(DeniedShapes.normalize(input))
                .containsExactly(QueryShape.JOIN, QueryShape.WINDOW_FUNCTION);
        assertThat(DeniedShapes.normalize(null)).isEmpty();
        assertThat(DeniedShapes.normalize(List.of())).isEmpty();
    }

    @Test
    void unionKeepsADenialFromEitherSide() {
        assertThat(DeniedShapes.union(List.of(QueryShape.HAVING), List.of(QueryShape.JOIN, QueryShape.HAVING)))
                .containsExactly(QueryShape.JOIN, QueryShape.HAVING);
        assertThat(DeniedShapes.union(null, null)).isEmpty();
    }

    @Test
    void namesRoundTripThroughStorage() {
        var names = DeniedShapes.toNames(List.of(QueryShape.CTE, QueryShape.JOIN, QueryShape.CTE));

        assertThat(names).containsExactly("JOIN", "CTE");
        assertThat(DeniedShapes.fromNames(List.of(names))).containsExactly(QueryShape.JOIN, QueryShape.CTE);
        assertThat(DeniedShapes.toNames(List.of())).isNull();
        assertThat(DeniedShapes.toNames(null)).isNull();
        assertThat(DeniedShapes.fromNames(null)).isEmpty();
        assertThatThrownBy(() -> DeniedShapes.fromNames(List.of("NOPE")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectedReturnsTheDeniedShapesTheQueryHas() {
        var query = parsed(QueryType.SELECT, Set.of(QueryShape.JOIN, QueryShape.AGGREGATE), true);

        assertThat(DeniedShapes.rejected(List.of(QueryShape.AGGREGATE, QueryShape.UNION, QueryShape.JOIN), query))
                .containsExactly(QueryShape.JOIN, QueryShape.AGGREGATE);
        assertThat(DeniedShapes.rejected(List.of(QueryShape.UNION), query)).isEmpty();
    }

    @Test
    void rejectedFailsClosedOnAnUnanalyzedParse() {
        var query = parsed(QueryType.SELECT, Set.of(), false);

        assertThat(DeniedShapes.rejected(List.of(QueryShape.UNION, QueryShape.JOIN), query))
                .containsExactly(QueryShape.JOIN, QueryShape.UNION);
    }

    @Test
    void rejectedIgnoresAnEmptyDenyListOtherStatementsAndANullParse() {
        assertThat(DeniedShapes.rejected(List.of(), parsed(QueryType.SELECT, Set.of(), false))).isEmpty();
        assertThat(DeniedShapes.rejected(List.of(QueryShape.JOIN), parsed(QueryType.OTHER, Set.of(), false)))
                .isEmpty();
        assertThat(DeniedShapes.rejected(List.of(QueryShape.JOIN), null)).isEmpty();
    }

    @Test
    void theLegacyParseResultConstructorsReportShapesAsNotAnalyzed() {
        var eightArg = new SqlParseResult(QueryType.SELECT, false, List.of("sql"), Set.of(), false,
                false, Set.of(), true);
        var sixArg = new SqlParseResult(QueryType.SELECT, false, List.of("sql"), Set.of(), true, true);

        assertThat(eightArg.shapesAnalyzed()).isFalse();
        assertThat(eightArg.shapes()).isEmpty();
        assertThat(sixArg.shapesAnalyzed()).isFalse();
        assertThat(new SqlParseResult(QueryType.SELECT, false, List.of("sql"), Set.of(), false,
                false, Set.of(), true, null, true).shapes()).isEmpty();
    }
}
