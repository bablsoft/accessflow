package com.bablsoft.accessflow.mcp.internal.config;

import com.bablsoft.accessflow.mcp.internal.tools.McpReviewToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers AccessFlow's @Tool-annotated tool services with the Spring AI stateless MCP server
 * starter. The starter's auto-configuration picks up any {@link ToolCallbackProvider} bean and
 * exposes its callbacks over the MCP transport.
 */
@Configuration
class McpServerConfiguration {

    @Bean
    ToolCallbackProvider accessFlowMcpToolCallbacks(McpToolService queryTools,
                                                    McpReviewToolService reviewTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(queryTools, reviewTools)
                .build();
    }
}
