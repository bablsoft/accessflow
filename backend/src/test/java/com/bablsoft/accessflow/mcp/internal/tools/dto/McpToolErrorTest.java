package com.bablsoft.accessflow.mcp.internal.tools.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolErrorTest {

    @Test
    void permissionDeniedCarriesTheDocumentedCode() {
        var error = McpToolError.permissionDenied("no");
        assertThat(error.code()).isEqualTo("permission_denied");
        assertThat(error.message()).isEqualTo("no");
    }
}
