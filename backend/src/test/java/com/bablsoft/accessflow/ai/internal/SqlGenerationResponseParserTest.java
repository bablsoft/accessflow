package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGenerationResponseParserTest {

    private final SqlGenerationResponseParser parser =
            new SqlGenerationResponseParser(JsonMapper.builder().build());

    @Test
    void parsesStrictJsonEnvelope() {
        var result = parser.parse("{\"sql\":\"SELECT 1\"}", AiProviderType.OPENAI, "gpt-4o", 12, 8);

        assertThat(result.sql()).isEqualTo("SELECT 1");
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(result.aiModel()).isEqualTo("gpt-4o");
        assertThat(result.promptTokens()).isEqualTo(12);
        assertThat(result.completionTokens()).isEqualTo(8);
    }

    @Test
    void stripsJsonFences() {
        var raw = "```json\n{\"sql\":\"SELECT 2\"}\n```";

        var result = parser.parse(raw, AiProviderType.ANTHROPIC, "claude", 1, 1);

        assertThat(result.sql()).isEqualTo("SELECT 2");
    }

    @Test
    void stripsSqlFences() {
        var raw = "```sql\n{\"sql\":\"SELECT 3\"}\n```";

        var result = parser.parse(raw, AiProviderType.OLLAMA, "llama", 1, 1);

        assertThat(result.sql()).isEqualTo("SELECT 3");
    }

    @Test
    void trimsWhitespaceInSqlValue() {
        var result = parser.parse("{\"sql\":\"  SELECT 4  \"}", AiProviderType.OPENAI, "m", 0, 0);

        assertThat(result.sql()).isEqualTo("SELECT 4");
    }

    @Test
    void throwsWhenRawTextIsNull() {
        assertThatThrownBy(() -> parser.parse(null, AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void throwsWhenRawTextIsBlank() {
        assertThatThrownBy(() -> parser.parse("   ", AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void throwsWhenNotJson() {
        assertThatThrownBy(() -> parser.parse("just some text", AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void throwsWhenRootIsNotObject() {
        assertThatThrownBy(() -> parser.parse("[\"SELECT 1\"]", AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void throwsWhenSqlFieldMissing() {
        assertThatThrownBy(() -> parser.parse("{\"foo\":\"bar\"}", AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("'sql'");
    }

    @Test
    void throwsWhenSqlFieldNotString() {
        assertThatThrownBy(() -> parser.parse("{\"sql\":123}", AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("'sql'");
    }

    @Test
    void throwsWhenSqlFieldBlank() {
        assertThatThrownBy(() -> parser.parse("{\"sql\":\"   \"}", AiProviderType.OPENAI, "m", 0, 0))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("non-blank");
    }
}
