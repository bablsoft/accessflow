package com.partqam.accessflow.ai.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisExceptionsTest {

    @Test
    void analysisExceptionRetainsMessageAndCause() {
        var cause = new RuntimeException("root");
        var ex = new AiAnalysisException("boom", cause);

        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void analysisExceptionMessageOnly() {
        var ex = new AiAnalysisException("only message");
        assertThat(ex.getMessage()).isEqualTo("only message");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void parseExceptionRetainsMessageAndCause() {
        var cause = new IllegalStateException("nope");
        var ex = new AiAnalysisParseException("bad json", cause);

        assertThat(ex.getMessage()).isEqualTo("bad json");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void parseExceptionMessageOnly() {
        var ex = new AiAnalysisParseException("bad json");
        assertThat(ex.getMessage()).isEqualTo("bad json");
        assertThat(ex.getCause()).isNull();
    }
}
