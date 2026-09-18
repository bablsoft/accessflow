package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.mcp.internal.tools.dto.McpToolError;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountToolPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces a service account's MCP tool allow-list at invocation (#872). Wraps every callback the
 * {@code MethodToolCallbackProvider} emits, so a tool added later is covered by construction and —
 * because the policy denies a name outside the {@code McpToolName} catalog — defaults closed.
 *
 * <p>The caller is read from the {@code SecurityContext}. That is safe because the stateless MCP
 * auto-configuration sets {@code immediateExecution(true)} whenever it runs in a servlet
 * environment: the tool body runs inline on the request thread, the same property
 * {@link McpCurrentUser} already relies on. {@code McpToolAllowListIntegrationTest} pins it.
 *
 * <p>A denial is <em>returned</em> as the documented {@code { code, message }} shape rather than
 * thrown, so the client sees a normal tool result it can reason about instead of an
 * {@code isError} envelope. {@code tools/list} is deliberately untouched — the SDK's stateless
 * list handler ignores the transport context, so every caller still sees every tool.
 */
public final class GuardedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);
    static final String DENIAL_MESSAGE_KEY = "error.mcp.tool_not_allowed";

    private final ToolCallback delegate;
    private final ServiceAccountToolPolicyService policy;
    private final McpCurrentUser currentUser;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public GuardedToolCallback(ToolCallback delegate,
                               ServiceAccountToolPolicyService policy,
                               McpCurrentUser currentUser,
                               MessageSource messageSource,
                               ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.policy = policy;
        this.currentUser = currentUser;
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        var userId = currentUser.userId();
        var toolName = delegate.getToolDefinition().name();
        if (!policy.isAllowed(userId, toolName)) {
            log.debug("MCP tool {} denied for user {} by allow-list", toolName, userId);
            var message = messageSource.getMessage(DENIAL_MESSAGE_KEY, new Object[] {toolName},
                    LocaleContextHolder.getLocale());
            return objectMapper.writeValueAsString(McpToolError.permissionDenied(message));
        }
        return delegate.call(toolInput, toolContext);
    }
}
