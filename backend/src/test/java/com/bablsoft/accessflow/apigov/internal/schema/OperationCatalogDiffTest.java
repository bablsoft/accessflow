package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.CLASSIFICATION_FLIPPED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.DEPRECATED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.OPERATION_ADDED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.OPERATION_REMOVED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.SHAPE_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;

class OperationCatalogDiffTest {

    private final OperationCatalogDiff diff = new OperationCatalogDiff(new ObjectMapper());

    private static ApiOperation op(String id) {
        return new ApiOperation(id, "GET", "/" + id, "summary", false, null, null, List.of("t"), null);
    }

    private static ApiOperation withShapes(ApiOperation o, String req, String resp) {
        return new ApiOperation(o.operationId(), o.verb(), o.path(), o.summary(), o.write(), req, resp,
                o.tags(), o.deprecated());
    }

    private static ApiOperation withDeprecated(ApiOperation o, Boolean deprecated) {
        return new ApiOperation(o.operationId(), o.verb(), o.path(), o.summary(), o.write(),
                o.requestSchema(), o.responseSchema(), o.tags(), deprecated);
    }

    private static ApiOperation withVerbPath(ApiOperation o, String verb, String path, boolean write) {
        return new ApiOperation(o.operationId(), verb, path, o.summary(), write, o.requestSchema(),
                o.responseSchema(), o.tags(), o.deprecated());
    }

    private List<ApiSchemaDriftKind> kinds(ApiOperation before, ApiOperation after) {
        return diff.diff(List.of(before), List.of(after)).stream().map(OperationDiffFinding::kind).toList();
    }

    @Test
    void identicalCatalogsProduceNoFindings() {
        var ops = List.of(op("a"), withShapes(op("b"), "{\"x\":1}", "Msg"));
        assertThat(diff.diff(ops, List.copyOf(ops))).isEmpty();
    }

    @Test
    void emptyAndNullListsAreHandled() {
        assertThat(diff.diff(List.of(), List.of())).isEmpty();
        assertThat(diff.diff(null, null)).isEmpty();
        assertThat(diff.diff(List.of(), List.of(op("a"))))
                .containsExactly(new OperationDiffFinding(OPERATION_ADDED, "a", null, op("a")));
        assertThat(diff.diff(List.of(op("a")), List.of()))
                .containsExactly(new OperationDiffFinding(OPERATION_REMOVED, "a", op("a"), null));
    }

    @Test
    void addedOperationCarriesOnlyAfter() {
        var findings = diff.diff(List.of(op("a")), List.of(op("a"), op("b")));
        assertThat(findings).containsExactly(new OperationDiffFinding(OPERATION_ADDED, "b", null, op("b")));
    }

    @Test
    void removedOperationCarriesOnlyBefore() {
        var findings = diff.diff(List.of(op("a"), op("b")), List.of(op("b")));
        assertThat(findings).containsExactly(new OperationDiffFinding(OPERATION_REMOVED, "a", op("a"), null));
    }

    @Test
    void writeFlipWithSameVerbIsClassificationFlippedOnly() {
        var before = op("a");
        var after = new ApiOperation("a", "GET", "/a", "summary", true, null, null, List.of("t"), null);
        assertThat(diff.diff(List.of(before), List.of(after)))
                .containsExactly(new OperationDiffFinding(CLASSIFICATION_FLIPPED, "a", before, after));
    }

    @Test
    void verbChangeIsShapeChanged() {
        assertThat(kinds(op("a"), withVerbPath(op("a"), "HEAD", "/a", false))).containsExactly(SHAPE_CHANGED);
    }

    @Test
    void pathChangeIsShapeChanged() {
        assertThat(kinds(op("a"), withVerbPath(op("a"), "GET", "/v2/a", false))).containsExactly(SHAPE_CHANGED);
    }

    @Test
    void requestShapeChangeWithBothSidesKnownIsShapeChanged() {
        assertThat(kinds(withShapes(op("a"), "{\"x\":1}", null), withShapes(op("a"), "{\"x\":2}", null)))
                .containsExactly(SHAPE_CHANGED);
    }

    @Test
    void responseShapeChangeWithBothSidesKnownIsShapeChanged() {
        assertThat(kinds(withShapes(op("a"), null, "{\"type\":\"object\"}"),
                withShapes(op("a"), null, "{\"type\":\"array\"}")))
                .containsExactly(SHAPE_CHANGED);
    }

    @Test
    void requestAndResponseBothChangedYieldOneShapeChanged() {
        assertThat(kinds(withShapes(op("a"), "{\"a\":1}", "{\"b\":1}"), withShapes(op("a"), "{\"a\":2}", "{\"b\":2}")))
                .containsExactly(SHAPE_CHANGED);
    }

    @Test
    void nullShapeOnEitherSideIsUnknownNotChanged() {
        assertThat(kinds(withShapes(op("a"), null, null), withShapes(op("a"), "{\"x\":1}", "Msg"))).isEmpty();
        assertThat(kinds(withShapes(op("a"), "{\"x\":1}", "Msg"), withShapes(op("a"), null, null))).isEmpty();
        assertThat(kinds(withShapes(op("a"), null, null), withShapes(op("a"), null, null))).isEmpty();
    }

    @Test
    void keyOrderAndWhitespaceOnlyJsonDifferenceIsNoFinding() {
        assertThat(kinds(withShapes(op("a"), "{\"x\":1,\"y\":{\"z\":true}}", null),
                withShapes(op("a"), " {\n  \"y\" : { \"z\" : true },\n  \"x\" : 1\n}", null)))
                .isEmpty();
    }

    @Test
    void nonJsonShapesAreComparedAsTrimmedStrings() {
        assertThat(kinds(withShapes(op("a"), "CreatePetRequest", "Pet"),
                withShapes(op("a"), "  CreatePetRequest\n", " Pet "))).isEmpty();
        assertThat(kinds(withShapes(op("a"), "CreatePetRequest", null),
                withShapes(op("a"), "CreatePetRequestV2", null))).containsExactly(SHAPE_CHANGED);
        assertThat(kinds(withShapes(op("a"), "Pet", null), withShapes(op("a"), "{\"x\":1}", null)))
                .containsExactly(SHAPE_CHANGED);
        assertThat(kinds(withShapes(op("a"), "  ", null), withShapes(op("a"), "", null))).isEmpty();
    }

    @Test
    void deprecationSetIsDeprecated() {
        assertThat(kinds(withDeprecated(op("a"), null), withDeprecated(op("a"), true))).containsExactly(DEPRECATED);
        assertThat(kinds(withDeprecated(op("a"), false), withDeprecated(op("a"), true))).containsExactly(DEPRECATED);
        assertThat(kinds(withDeprecated(op("a"), true), withDeprecated(op("a"), true))).isEmpty();
    }

    @Test
    void deprecationClearedIsNoFinding() {
        assertThat(kinds(withDeprecated(op("a"), true), withDeprecated(op("a"), false))).isEmpty();
        assertThat(kinds(withDeprecated(op("a"), true), withDeprecated(op("a"), null))).isEmpty();
    }

    @Test
    void summaryAndTagsChangeIsNoFinding() {
        var after = new ApiOperation("a", "GET", "/a", "new summary", false, null, null, List.of("x", "y"), null);
        assertThat(kinds(op("a"), after)).isEmpty();
    }

    @Test
    void duplicateIdsWithinASideKeepTheFirstOccurrence() {
        var first = op("a");
        var dup = withVerbPath(op("a"), "DELETE", "/gone", true);
        assertThat(diff.diff(List.of(first, dup), List.of(first))).isEmpty();
        assertThat(diff.diff(List.of(first), List.of(first, dup))).isEmpty();
    }

    @Test
    void nullEntriesAndNullIdsAreIgnored() {
        var withNulls = new ArrayList<ApiOperation>();
        withNulls.add(null);
        withNulls.add(new ApiOperation(null, "GET", "/x", null, false, null, null));
        withNulls.add(op("a"));
        assertThat(diff.diff(withNulls, List.of(op("a")))).isEmpty();
    }

    @Test
    void outputIsSortedByOperationIdThenKindOrdinal() {
        var b = withDeprecated(op("b"), null);
        var bAfter = new ApiOperation("b", "POST", "/b", "summary", true, null, null, List.of("t"), true);
        var findings = diff.diff(List.of(op("z"), b, op("c")), List.of(bAfter, op("a"), op("c")));

        assertThat(findings).containsExactly(
                new OperationDiffFinding(OPERATION_ADDED, "a", null, op("a")),
                new OperationDiffFinding(SHAPE_CHANGED, "b", b, bAfter),
                new OperationDiffFinding(DEPRECATED, "b", b, bAfter),
                new OperationDiffFinding(CLASSIFICATION_FLIPPED, "b", b, bAfter),
                new OperationDiffFinding(OPERATION_REMOVED, "z", op("z"), null));
        assertThat(diff.diff(List.of(op("c"), b, op("z")), List.of(op("c"), op("a"), bAfter)))
                .isEqualTo(findings);
    }

    @Test
    void contentHashIsStableForPermutedInput() {
        var a = op("a");
        var b = withShapes(op("b"), "{\"x\":1}", "Msg");
        var hash = diff.contentHash(List.of(a, b));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(diff.contentHash(List.of(b, a))).isEqualTo(hash);
    }

    @Test
    void contentHashChangesWithAnyField() {
        var base = diff.contentHash(List.of(op("a")));
        assertThat(diff.contentHash(List.of(withDeprecated(op("a"), true)))).isNotEqualTo(base);
        assertThat(diff.contentHash(List.of(new ApiOperation("a", "GET", "/a", "other", false, null, null,
                List.of("t"), null)))).isNotEqualTo(base);
        assertThat(diff.contentHash(List.of(new ApiOperation("a", "GET", "/a", "summary", false, null, null,
                null, null)))).isNotEqualTo(base);
    }

    @Test
    void contentHashHandlesEmptyAndNullLists() {
        assertThat(diff.contentHash(List.of())).isEqualTo(diff.contentHash(null)).hasSize(64);
        var withNull = new ArrayList<ApiOperation>();
        withNull.add(null);
        withNull.add(op("a"));
        assertThat(diff.contentHash(withNull)).isEqualTo(diff.contentHash(List.of(op("a"))));
    }

    @Test
    void contentHashIsIndependentOfMapperInclusionConfig() {
        var nonNull = JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        var other = new OperationCatalogDiff(nonNull);
        var ops = List.of(op("a"), new ApiOperation("b", "POST", "/b", null, true, null, null, null, null));
        assertThat(other.contentHash(ops)).isEqualTo(diff.contentHash(ops));
    }
}
