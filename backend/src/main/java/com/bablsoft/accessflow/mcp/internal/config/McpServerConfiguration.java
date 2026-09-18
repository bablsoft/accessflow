package com.bablsoft.accessflow.mcp.internal.config;

import com.bablsoft.accessflow.mcp.internal.tools.GuardedToolCallback;
import com.bablsoft.accessflow.mcp.internal.tools.McpCurrentUser;
import com.bablsoft.accessflow.mcp.internal.tools.McpDataToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpReviewToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpToolService;
import com.bablsoft.accessflow.serviceaccounts.api.OnBehalfOfPrincipalService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountToolPolicyService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

/**
 * Registers AccessFlow's @Tool-annotated tool services with the Spring AI stateless MCP server
 * starter. The starter's auto-configuration picks up any {@link ToolCallbackProvider} bean and
 * exposes its callbacks over the MCP transport.
 *
 * <p>Every callback the {@link MethodToolCallbackProvider} emits is wrapped in a
 * {@link GuardedToolCallback} before it is exposed (#872), so no tool can be invoked past a
 * service account's allow-list — including one added later. The raw provider is deliberately not
 * a bean of its own: the auto-configuration aggregates every provider it finds, and an unwrapped
 * one would re-open the whole surface. {@code tools/list} still advertises every tool to every
 * caller; the SDK's stateless list handler cannot see who is asking.
 */
@Configuration
class McpServerConfiguration {

    @Bean
    ToolCallbackProvider accessFlowMcpToolCallbacks(McpToolService queryTools,
                                                    McpReviewToolService reviewTools,
                                                    McpDataToolService dataTools,
                                                    ServiceAccountToolPolicyService toolPolicy,
                                                    McpCurrentUser currentUser,
                                                    OnBehalfOfPrincipalService onBehalfOfPrincipalService,
                                                    MessageSource messageSource,
                                                    ObjectMapper objectMapper) {
        var unguarded = MethodToolCallbackProvider.builder()
                .toolObjects(queryTools, reviewTools, dataTools)
                .build();
        var guarded = Arrays.stream(unguarded.getToolCallbacks())
                .map(callback -> new GuardedToolCallback(
                        callback, toolPolicy, currentUser, onBehalfOfPrincipalService, messageSource,
                        objectMapper))
                .toArray(ToolCallback[]::new);
        return () -> guarded.clone();
    }
}
