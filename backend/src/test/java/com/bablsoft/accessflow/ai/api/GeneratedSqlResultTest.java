package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedSqlResultTest {

    @Test
    void fiveArgConstructorLeavesSyntaxNull() {
        var result = new GeneratedSqlResult("SELECT 1", AiProviderType.OPENAI, "gpt", 10, 4);

        assertThat(result.sql()).isEqualTo("SELECT 1");
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(result.aiModel()).isEqualTo("gpt");
        assertThat(result.promptTokens()).isEqualTo(10);
        assertThat(result.completionTokens()).isEqualTo(4);
        assertThat(result.syntax()).isNull();
    }

    @Test
    void withSyntaxCopiesAllFieldsAndSetsSyntax() {
        var base = new GeneratedSqlResult("db.users.find({})", AiProviderType.ANTHROPIC, "claude", 20, 8);

        var withSyntax = base.withSyntax("shell");

        assertThat(withSyntax.syntax()).isEqualTo("shell");
        assertThat(withSyntax.sql()).isEqualTo("db.users.find({})");
        assertThat(withSyntax.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(withSyntax.aiModel()).isEqualTo("claude");
        assertThat(withSyntax.promptTokens()).isEqualTo(20);
        assertThat(withSyntax.completionTokens()).isEqualTo(8);
        // Original is unchanged.
        assertThat(base.syntax()).isNull();
    }
}
