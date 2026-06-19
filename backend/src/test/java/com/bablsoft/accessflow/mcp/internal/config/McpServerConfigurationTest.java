package com.bablsoft.accessflow.mcp.internal.config;

import com.bablsoft.accessflow.mcp.internal.tools.McpCurrentUser;
import com.bablsoft.accessflow.mcp.internal.tools.McpDataToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpReviewToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpToolService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerConfigurationTest {

    @Test
    void provider_includes_all_tool_objects() throws Exception {
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
        var provider = config.accessFlowMcpToolCallbacks(queryTools, reviewTools, dataTools);
        assertThat(provider).isNotNull();
        // Verify the callbacks cover every tool object's @Tool methods (7 + 2 + 3 = 12).
        assertThat(provider.getToolCallbacks()).hasSize(12);
    }
}
