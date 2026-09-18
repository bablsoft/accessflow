package com.bablsoft.accessflow.mcp.internal.config;

import com.bablsoft.accessflow.mcp.internal.tools.GuardedToolCallback;
import com.bablsoft.accessflow.mcp.internal.tools.McpCurrentUser;
import com.bablsoft.accessflow.mcp.internal.tools.McpDataToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpReviewToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpToolService;
import com.bablsoft.accessflow.serviceaccounts.api.McpToolName;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountToolPolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerConfigurationTest {

    @Test
    void provider_wraps_every_tool_in_a_guard_and_keeps_the_advertised_names() {
        var config = new McpServerConfiguration();
        var queryTools = new McpToolService(new McpCurrentUser(),
                Mockito.mock(com.bablsoft.accessflow.core.api.DatasourceAdminService.class),
                Mockito.mock(com.bablsoft.accessflow.core.api.QueryRequestLookupService.class),
                Mockito.mock(com.bablsoft.accessflow.core.api.QueryResultPersistenceService.class),
                Mockito.mock(com.bablsoft.accessflow.workflow.api.QuerySubmissionService.class),
                Mockito.mock(com.bablsoft.accessflow.workflow.api.QueryLifecycleService.class));
        var reviewTools = new McpReviewToolService(new McpCurrentUser(),
                Mockito.mock(com.bablsoft.accessflow.workflow.api.ReviewService.class));
        var dataTools = new McpDataToolService(new McpCurrentUser(),
                Mockito.mock(com.bablsoft.accessflow.core.api.DatasourceAdminService.class),
                Mockito.mock(com.bablsoft.accessflow.proxy.api.QueryParser.class),
                Mockito.mock(com.bablsoft.accessflow.proxy.api.SampleDataService.class),
                Mockito.mock(com.bablsoft.accessflow.audit.api.AuditLogService.class));
        var provider = config.accessFlowMcpToolCallbacks(queryTools, reviewTools, dataTools,
                Mockito.mock(ServiceAccountToolPolicyService.class), new McpCurrentUser(),
                new StaticMessageSource(), new ObjectMapper());
        assertThat(provider).isNotNull();
        // Every tool object's @Tool methods (7 + 2 + 3 = 12), each behind the allow-list guard.
        var callbacks = provider.getToolCallbacks();
        assertThat(callbacks).hasSize(12);
        assertThat(callbacks).allSatisfy(cb -> assertThat(cb).isInstanceOf(GuardedToolCallback.class));
        assertThat(Arrays.stream(callbacks).map(ToolCallback::getToolDefinition).map(d -> d.name()))
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(McpToolName.values()).map(McpToolName::toolName).toList());
    }
}
