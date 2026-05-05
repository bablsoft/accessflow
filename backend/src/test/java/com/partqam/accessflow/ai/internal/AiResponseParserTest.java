package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import com.partqam.accessflow.ai.api.AiIssue;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResponseParserTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AiResponseParser parser = new AiResponseParser(objectMapper);

    @Test
    void parsesValidResponse() {
        var json = """
                {
                  "risk_score": 75,
                  "risk_level": "HIGH",
                  "summary": "SELECT * with no LIMIT.",
                  "issues": [
                    {
                      "severity": "HIGH",
                      "category": "SELECT_STAR",
                      "message": "Returns all columns.",
                      "suggestion": "List columns explicitly."
                    }
                  ],
                  "missing_indexes_detected": false,
                  "affects_row_estimate": null
                }
                """;

        var result = parser.parse(json, AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 100, 50);

        assertThat(result.riskScore()).isEqualTo(75);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.summary()).isEqualTo("SELECT * with no LIMIT.");
        assertThat(result.missingIndexesDetected()).isFalse();
        assertThat(result.affectsRowEstimate()).isNull();
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(result.aiModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(result.promptTokens()).isEqualTo(100);
        assertThat(result.completionTokens()).isEqualTo(50);
        assertThat(result.issues()).hasSize(1);
        var issue = result.issues().get(0);
        assertThat(issue.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(issue.category()).isEqualTo("SELECT_STAR");
        assertThat(issue.message()).isEqualTo("Returns all columns.");
        assertThat(issue.suggestion()).isEqualTo("List columns explicitly.");
    }

    @Test
    void stripsLeadingAndTrailingMarkdownFences() {
        var json = """
                ```json
                {"risk_score":0,"risk_level":"LOW","summary":"ok","issues":[],"missing_indexes_detected":false,"affects_row_estimate":null}
                ```
                """;

        var result = parser.parse(json, AiProviderType.ANTHROPIC, "m", 1, 1);

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void stripsBareTripleBackticks() {
        var json = """
                ```
                {"risk_score":10,"risk_level":"LOW","summary":"ok","issues":[],"missing_indexes_detected":false,"affects_row_estimate":null}
                ```
                """;

        var result = parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0);

        assertThat(result.riskScore()).isEqualTo(10);
    }

    @Test
    void parsesAffectsRowEstimateAsLong() {
        var json = "{\"risk_score\":50,\"risk_level\":\"MEDIUM\",\"summary\":\"x\","
                + "\"issues\":[],\"missing_indexes_detected\":true,\"affects_row_estimate\":1234567}";

        var result = parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0);

        assertThat(result.affectsRowEstimate()).isEqualTo(1234567L);
        assertThat(result.missingIndexesDetected()).isTrue();
    }

    @Test
    void rejectsNonObjectRoot() {
        assertThatThrownBy(() -> parser.parse("[]", AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json}", AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsNullOrBlankInput() {
        assertThatThrownBy(() -> parser.parse(null, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
        assertThatThrownBy(() -> parser.parse("   ", AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void rejectsRiskScoreOutOfRange() {
        var json = "{\"risk_score\":150,\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":[],\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("risk_score must be in [0, 100]");
    }

    @Test
    void rejectsInvalidRiskLevel() {
        var json = "{\"risk_score\":10,\"risk_level\":\"BANANAS\",\"summary\":\"x\","
                + "\"issues\":[],\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("LOW|MEDIUM|HIGH|CRITICAL");
    }

    @Test
    void rejectsBlankSummary() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\",\"summary\":\"   \","
                + "\"issues\":[],\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void rejectsMissingFields() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\"}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void rejectsNonIntegerRiskScore() {
        var json = "{\"risk_score\":\"high\",\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":[],\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void rejectsNonBooleanMissingIndexes() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":[],\"missing_indexes_detected\":\"yes\",\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void rejectsNonIntegerAffectsRowEstimate() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":[],\"missing_indexes_detected\":false,\"affects_row_estimate\":\"lots\"}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void rejectsNonArrayIssues() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":\"none\",\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("issues");
    }

    @Test
    void rejectsIssueItemThatIsNotObject() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":[\"oops\"],\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        assertThatThrownBy(() -> parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("issues[0]");
    }

    @Test
    void treatsNullIssuesAsEmpty() {
        var json = "{\"risk_score\":10,\"risk_level\":\"LOW\",\"summary\":\"x\","
                + "\"issues\":null,\"missing_indexes_detected\":false,\"affects_row_estimate\":null}";

        var result = parser.parse(json, AiProviderType.ANTHROPIC, "m", 0, 0);

        assertThat(result.issues()).isEmpty();
    }

    @Test
    void issuesAsJsonRoundTrips() {
        var issues = List.of(new AiIssue(RiskLevel.HIGH, "X", "y", "z"));
        var json = parser.issuesAsJson(issues);
        assertThat(json).contains("\"severity\":\"HIGH\"")
                .contains("\"category\":\"X\"")
                .contains("\"message\":\"y\"")
                .contains("\"suggestion\":\"z\"");
    }
}
