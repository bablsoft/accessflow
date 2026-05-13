package com.bablsoft.accessflow.mcp.internal.config;

import com.bablsoft.accessflow.mcp.internal.tools.McpCurrentUser;
import com.bablsoft.accessflow.mcp.internal.tools.McpReviewToolService;
import com.bablsoft.accessflow.mcp.internal.tools.McpToolService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerConfigurationTest {

    @Test
    void provider_includes_both_tool_objects() throws Exception {
        var config = new McpServerConfiguration();
        var queryTools = new McpToolService(new McpCurrentUser(),
                Mockito.mock(com.bablsoft.accessflow.core.api.DatasourceAdminService.class),
                Mockito.mock(com.bablsoft.accessflow.core.api.QueryRequestLookupService.class),
                Mockito.mock(com.bablsoft.accessflow.core.api.QueryResultPersistenceService.class),
                Mockito.mock(com.bablsoft.accessflow.workflow.api.QuerySubmissionService.class),
                Mockito.mock(com.bablsoft.accessflow.workflow.api.QueryLifecycleService.class));
        var reviewTools = new McpReviewToolService(new McpCurrentUser(),
                Mockito.mock(com.bablsoft.accessflow.workflow.api.ReviewService.class));
        var provider = config.accessFlowMcpToolCallbacks(queryTools, reviewTools);
        assertThat(provider).isNotNull();
        // Verify the callbacks exposed cover both tool objects' @Tool methods (7 + 2 = 9).
        assertThat(provider.getToolCallbacks()).hasSize(9);
    }
}
