package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperationFilterMatcherTest {

    private final OperationFilterMatcher matcher = new OperationFilterMatcher();

    private static ApiOperation op(String id, String verb, String path) {
        return new ApiOperation(id, verb, path, null, !verb.equalsIgnoreCase("GET"), null, null);
    }

    private static ApiOperation op(String id, String verb, String path, List<String> tags, Boolean deprecated) {
        return new ApiOperation(id, verb, path, null, false, null, null, tags, deprecated);
    }

    private static OperationFilter filter(List<String> incPath, List<String> excPath, List<String> incVerb,
                                          List<String> excVerb, List<String> incId, List<String> excId,
                                          List<String> incTag, List<String> excTag, boolean excDep) {
        return new OperationFilter(incPath, excPath, incVerb, excVerb, incId, excId, incTag, excTag, excDep);
    }

    private List<String> ids(List<ApiOperation> ops) {
        return ops.stream().map(ApiOperation::operationId).toList();
    }

    private final List<ApiOperation> catalog = List.of(
            op("listPets", "GET", "/pets"),
            op("createPet", "POST", "/pets"),
            op("internalSync", "POST", "/internal/sync"),
            op("debugPing", "GET", "/api/debug/ping"));

    @Test
    void emptyFilterKeepsEverything() {
        assertThat(matcher.apply(catalog, OperationFilter.EMPTY)).hasSize(4);
    }

    @Test
    void nullFilterKeepsEverything() {
        assertThat(matcher.apply(catalog, null)).hasSize(4);
    }

    @Test
    void excludePathGlobDropsMatchingPaths() {
        var f = filter(null, List.of("/internal/**"), null, null, null, null, null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).containsExactly("listPets", "createPet", "debugPing");
    }

    @Test
    void excludePathGlobMatchesAcrossSlashesWithSingleStar() {
        var f = filter(null, List.of("*/debug/*"), null, null, null, null, null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).doesNotContain("debugPing");
    }

    @Test
    void excludeVerbIsExactCaseInsensitive() {
        var f = filter(null, null, null, List.of("post"), null, null, null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).containsExactly("listPets", "debugPing");
    }

    @Test
    void excludeOperationIdGlob() {
        var f = filter(null, null, null, null, null, List.of("internal*"), null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).doesNotContain("internalSync");
    }

    @Test
    void includePathRestrictsToMatches() {
        var f = filter(List.of("/pets"), null, null, null, null, null, null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).containsExactly("listPets", "createPet");
    }

    @Test
    void includeVerbRestrictsToMatches() {
        var f = filter(null, null, List.of("GET"), null, null, null, null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).containsExactly("listPets", "debugPing");
    }

    @Test
    void excludeWinsOverInclude() {
        // include everything under /pets, but exclude the write op by id — exclude wins.
        var f = filter(List.of("/pets"), null, null, null, null, List.of("createPet"), null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).containsExactly("listPets");
    }

    @Test
    void excludeDeprecatedDropsDeprecatedOnly() {
        var ops = List.of(
                op("a", "GET", "/a", null, false),
                op("b", "GET", "/b", null, true));
        var f = filter(null, null, null, null, null, null, null, null, true);
        assertThat(ids(matcher.apply(ops, f))).containsExactly("a");
    }

    @Test
    void excludeTagDropsByTagMembership() {
        var ops = List.of(
                op("a", "GET", "/a", List.of("public"), false),
                op("b", "GET", "/b", List.of("internal", "admin"), false));
        var f = filter(null, null, null, null, null, null, null, List.of("internal"), false);
        assertThat(ids(matcher.apply(ops, f))).containsExactly("a");
    }

    @Test
    void includeTagRestrictsByTagMembership() {
        var ops = List.of(
                op("a", "GET", "/a", List.of("public"), false),
                op("b", "GET", "/b", List.of("internal"), false));
        var f = filter(null, null, null, null, null, null, List.of("public"), null, false);
        assertThat(ids(matcher.apply(ops, f))).containsExactly("a");
    }

    @Test
    void dimensionsCombineWithAnd() {
        // keep GET verbs but drop the debug path — both constraints apply.
        var f = filter(null, List.of("*/debug/*"), List.of("GET"), null, null, null, null, null, false);
        assertThat(ids(matcher.apply(catalog, f))).containsExactly("listPets");
    }

    @Test
    void emptyCatalogReturnsEmpty() {
        assertThat(matcher.apply(List.of(), OperationFilter.EMPTY)).isEmpty();
    }
}
