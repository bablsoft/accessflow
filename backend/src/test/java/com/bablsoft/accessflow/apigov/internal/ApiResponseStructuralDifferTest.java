package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffUnsupportedException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResponseStructuralDifferTest {

    private final ApiResponseStructuralDiffer differ = new ApiResponseStructuralDiffer(JsonMapper.builder().build());

    private ApiResponseStructuralDiffer.Result json(String before, String after) {
        return differ.diff(before, "application/json", after, "application/json", ApiResponseDiffFormat.JSON, 500);
    }

    private ApiResponseStructuralDiffer.Result xml(String before, String after) {
        return differ.diff(before, "application/xml", after, "application/xml", ApiResponseDiffFormat.XML, 500);
    }

    @Test
    void identicalJsonHasNoEntries() {
        var result = json("{\"a\":{\"b\":[1,2]}}", "{\"a\":{\"b\":[1,2]}}");

        assertThat(result.entries()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void nestedObjectChangeUsesDotPath() {
        var result = json("{\"data\":{\"user\":{\"name\":\"a\"}}}", "{\"data\":{\"user\":{\"name\":\"b\"}}}");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("data.user.name", ApiResponseDiffKind.CHANGED, "\"a\"", "\"b\""));
    }

    @Test
    void arraysCompareByIndex() {
        var result = json("{\"items\":[1,2]}", "{\"items\":[1,3,4]}");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("items[1]", ApiResponseDiffKind.CHANGED, "2", "3"),
                new ApiResponseDiffEntry("items[2]", ApiResponseDiffKind.ADDED, null, "4"));
    }

    @Test
    void rootArrayPathsStartWithTheIndex() {
        var result = json("[{\"id\":1}]", "[{\"id\":2}]");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("[0].id", ApiResponseDiffKind.CHANGED, "1", "2"));
    }

    @Test
    void addedAndRemovedSubtreesAreReportedOnceAtTheirRoot() {
        var result = json("{\"gone\":{\"x\":1,\"y\":[1,2]},\"kept\":1}",
                "{\"kept\":1,\"fresh\":{\"z\":{\"w\":true}}}");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("gone", ApiResponseDiffKind.REMOVED, "{\"x\":1,\"y\":[1,2]}", null),
                new ApiResponseDiffEntry("fresh", ApiResponseDiffKind.ADDED, null, "{\"z\":{\"w\":true}}"));
    }

    @Test
    void typeChangeIsChanged() {
        var result = json("{\"id\":1}", "{\"id\":\"1\"}");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("id", ApiResponseDiffKind.CHANGED, "1", "\"1\""));
    }

    @Test
    void rootScalarChangeUsesTheRootPath() {
        var result = json("1", "2");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("$", ApiResponseDiffKind.CHANGED, "1", "2"));
    }

    @Test
    void entryCapSetsTruncated() {
        var result = differ.diff("{\"a\":1,\"b\":1,\"c\":1}", null, "{\"a\":2,\"b\":2,\"c\":2}", null,
                ApiResponseDiffFormat.JSON, 2);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exactlyCapEntriesIsNotTruncated() {
        var result = differ.diff("{\"a\":1,\"b\":1}", null, "{\"a\":2,\"b\":2}", null,
                ApiResponseDiffFormat.JSON, 2);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void longValuesAreClipped() {
        var longValue = "x".repeat(2000);
        var result = json("{\"v\":\"a\"}", "{\"v\":\"" + longValue + "\"}");

        assertThat(result.entries().getFirst().after()).hasSize(ApiResponseStructuralDiffer.MAX_VALUE_CHARS);
    }

    @Test
    void unparseableJsonIsUnsupported() {
        assertThatThrownBy(() -> json("{\"a\":1}", "{\"a\":"))
                .isInstanceOf(ApiResponseDiffUnsupportedException.class)
                .satisfies(ex -> assertThat(((ApiResponseDiffUnsupportedException) ex).contentType())
                        .isEqualTo("application/json"));
    }

    @Test
    void unparseableSideReportsItsOwnContentType() {
        assertThatThrownBy(() -> differ.diff("<html>bad gateway", "text/html", "{}", "application/json",
                ApiResponseDiffFormat.JSON, 500))
                .isInstanceOf(ApiResponseDiffUnsupportedException.class)
                .satisfies(ex -> assertThat(((ApiResponseDiffUnsupportedException) ex).contentType())
                        .isEqualTo("text/html"));
    }

    @Test
    void emptyOrNullJsonIsUnsupported() {
        assertThatThrownBy(() -> json("", "{}")).isInstanceOf(ApiResponseDiffUnsupportedException.class);
        assertThatThrownBy(() -> json(null, "{}")).isInstanceOf(ApiResponseDiffUnsupportedException.class);
    }

    @Test
    void xmlTextAndAttributeChanges() {
        var result = xml("<order id=\"1\"><total>10</total><note>hi</note></order>",
                "<order id=\"2\"><total>12</total><note>hi</note></order>");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("order[0].@id", ApiResponseDiffKind.CHANGED, "1", "2"),
                new ApiResponseDiffEntry("order[0].total[0].#text", ApiResponseDiffKind.CHANGED, "10", "12"));
    }

    @Test
    void xmlRepeatedSiblingsAreIndexedAndSubtreesReportedOnce() {
        var result = xml("<list><item><a>1</a></item><item><a>2</a></item></list>",
                "<list><item><a>1</a></item><extra><b>3</b></extra></list>");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("list[0].item[1]", ApiResponseDiffKind.REMOVED, "2", null),
                new ApiResponseDiffEntry("list[0].extra[0]", ApiResponseDiffKind.ADDED, null, "3"));
    }

    @Test
    void xmlAddedAndRemovedLeaves() {
        var result = xml("<a x=\"1\">text</a>", "<a y=\"2\"/>");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("a[0].@x", ApiResponseDiffKind.REMOVED, "1", null),
                new ApiResponseDiffEntry("a[0].#text", ApiResponseDiffKind.REMOVED, "text", null),
                new ApiResponseDiffEntry("a[0].@y", ApiResponseDiffKind.ADDED, null, "2"));
    }

    @Test
    void xmlDifferentRootsAreRemovedAndAdded() {
        var result = xml("<a/>", "<b><!-- note --><![CDATA[c]]></b>");

        assertThat(result.entries()).containsExactly(
                new ApiResponseDiffEntry("a[0]", ApiResponseDiffKind.REMOVED, "", null),
                new ApiResponseDiffEntry("b[0]", ApiResponseDiffKind.ADDED, null, "c"));
    }

    @Test
    void xmlEntryCapSetsTruncated() {
        var result = differ.diff("<r><a>1</a><b>1</b><c>1</c></r>", null, "<r><a>2</a><b>2</b><c>2</c></r>", null,
                ApiResponseDiffFormat.XML, 1);

        assertThat(result.entries()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void xmlWithDoctypeIsRejectedWithoutExpandingEntities() {
        var hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <r>&x;</r>""";

        assertThatThrownBy(() -> xml("<r/>", hostile)).isInstanceOf(ApiResponseDiffUnsupportedException.class);
    }

    @Test
    void malformedOrNullXmlIsUnsupported() {
        assertThatThrownBy(() -> xml("<r>", "<r/>")).isInstanceOf(ApiResponseDiffUnsupportedException.class);
        assertThatThrownBy(() -> xml(null, "<r/>")).isInstanceOf(ApiResponseDiffUnsupportedException.class);
    }
}
