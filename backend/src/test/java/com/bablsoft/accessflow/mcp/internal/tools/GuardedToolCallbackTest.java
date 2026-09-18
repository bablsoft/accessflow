package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountToolPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardedToolCallbackTest {

    private static final String TOOL = "submit_query";

    @Mock ToolCallback delegate;
    @Mock ServiceAccountToolPolicyService policy;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private GuardedToolCallback guarded;

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.addMessage(GuardedToolCallback.DENIAL_MESSAGE_KEY, Locale.ENGLISH,
                "Not allowed to call ''{0}''");
        guarded = new GuardedToolCallback(delegate, policy, new McpCurrentUser(), messages, objectMapper);
        SecurityContextHolder.clearContext();
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void allowedCallDelegatesWithTheSameToolContext() {
        authenticate(UserRoleType.ANALYST);
        stubDefinition();
        when(policy.isAllowed(userId, TOOL)).thenReturn(true);
        var context = new ToolContext(Map.of("exchange", "x"));
        when(delegate.call("{}", context)).thenReturn("{\"id\":\"1\"}");

        assertThat(guarded.call("{}", context)).isEqualTo("{\"id\":\"1\"}");
        verify(delegate).call("{}", context);
    }

    @Test
    void deniedCallReturnsStructuredPermissionDeniedAndNeverDelegates() {
        authenticate(UserRoleType.ANALYST);
        stubDefinition();
        when(policy.isAllowed(userId, TOOL)).thenReturn(false);

        var result = objectMapper.readTree(guarded.call("{}", new ToolContext(Map.of())));

        assertThat(result.get("code").asString()).isEqualTo("permission_denied");
        assertThat(result.get("message").asString()).isEqualTo("Not allowed to call 'submit_query'");
        verify(delegate, never()).call(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void singleArgumentCallGoesThroughTheGuard() {
        authenticate(UserRoleType.ANALYST);
        stubDefinition();
        when(policy.isAllowed(userId, TOOL)).thenReturn(false);
        assertThat(guarded.call("{}")).contains("permission_denied");
        verify(delegate, never()).call(any(), any());

        when(policy.isAllowed(userId, TOOL)).thenReturn(true);
        when(delegate.call("{}", null)).thenReturn("ok");
        assertThat(guarded.call("{}")).isEqualTo("ok");
    }

    // The policy owns the allow-list semantics (DefaultServiceAccountToolPolicyServiceTest); these
    // pin that the guard asks it with exactly the caller id and the wire name in each of the
    // cases the issue names, and honours its answer.
    @Test
    void honoursAnAllowingPolicyAnswer() {
        assertPolicyAnswerHonoured(true);
    }

    @Test
    void honoursADenyingPolicyAnswer() {
        assertPolicyAnswerHonoured(false);
    }

    @Test
    void unknownToolNameIsDenied() {
        authenticate(UserRoleType.ANALYST);
        when(delegate.getToolDefinition()).thenReturn(definition("thirteenth_tool"));
        when(policy.isAllowed(userId, "thirteenth_tool")).thenReturn(false);
        assertThat(guarded.call("{}", null)).contains("permission_denied");
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void humanJwtCallerIsUnaffected() {
        authenticate(UserRoleType.ADMIN);
        stubDefinition();
        when(policy.isAllowed(userId, TOOL)).thenReturn(true);
        when(delegate.call("{}", null)).thenReturn("ok");
        assertThat(guarded.call("{}", null)).isEqualTo("ok");
        verify(policy).isAllowed(userId, TOOL);
    }

    @Test
    void unauthenticatedContextFailsClosedBeforeConsultingThePolicy() {
        assertThatThrownBy(() -> guarded.call("{}", null))
                .isInstanceOf(McpAuthenticationException.class);
        verifyNoInteractions(policy);
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void definitionAndMetadataPassThrough() {
        var definition = definition(TOOL);
        var metadata = ToolMetadata.builder().returnDirect(true).build();
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.getToolMetadata()).thenReturn(metadata);

        assertThat(guarded.getToolDefinition()).isSameAs(definition);
        assertThat(guarded.getToolMetadata()).isSameAs(metadata);
    }

    private void assertPolicyAnswerHonoured(boolean allowed) {
        authenticate(UserRoleType.READONLY);
        stubDefinition();
        when(policy.isAllowed(userId, TOOL)).thenReturn(allowed);
        if (allowed) {
            when(delegate.call("{}", null)).thenReturn("ok");
            assertThat(guarded.call("{}", null)).isEqualTo("ok");
        } else {
            assertThat(guarded.call("{}", null)).contains("permission_denied");
            verify(delegate, never()).call(any(), any());
        }
        verify(policy).isAllowed(userId, TOOL);
    }

    private void stubDefinition() {
        when(delegate.getToolDefinition()).thenReturn(definition(TOOL));
    }

    private static ToolDefinition definition(String name) {
        return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
    }

    private void authenticate(UserRoleType role) {
        var claims = JwtClaims.forSystemRole(userId, "u@e.c", role, UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(claims, null, "ROLE_" + role.name()));
    }
}
