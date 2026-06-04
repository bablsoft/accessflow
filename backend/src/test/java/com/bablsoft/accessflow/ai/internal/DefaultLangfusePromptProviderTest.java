package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.LangfuseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLangfusePromptProviderTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock LangfuseConfigResolver configResolver;
    @Mock LangfuseClient client;

    private DefaultLangfusePromptProvider provider() {
        return new DefaultLangfusePromptProvider(configResolver, client,
                new LangfuseProperties(URI.create("https://cloud.langfuse.com"), null, null, null));
    }

    private static ResolvedLangfuseConfig resolved(boolean promptMgmt) {
        return new ResolvedLangfuseConfig("https://lf.example.com/", "pk", "sk", true, promptMgmt);
    }

    @Test
    void emptyWhenNameBlank() {
        assertThat(provider().resolve(ORG_ID, "  ", "production")).isEmpty();
    }

    @Test
    void emptyWhenLangfuseDisabled() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.empty());
        assertThat(provider().resolve(ORG_ID, "sql-analysis", "production")).isEmpty();
    }

    @Test
    void emptyWhenPromptManagementDisabled() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(false)));
        assertThat(provider().resolve(ORG_ID, "sql-analysis", "production")).isEmpty();
    }

    @Test
    void fetchesAndCachesSuccessfulPrompt() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        when(client.fetchPrompt(any(), eq("sql-analysis"), eq("production")))
                .thenReturn(Optional.of("TEMPLATE {{sql}}"));
        var provider = provider();

        assertThat(provider.resolve(ORG_ID, "sql-analysis", "production")).contains("TEMPLATE {{sql}}");
        // Second call served from cache — no second fetch.
        assertThat(provider.resolve(ORG_ID, "sql-analysis", "production")).contains("TEMPLATE {{sql}}");
        verify(client, times(1)).fetchPrompt(any(), eq("sql-analysis"), eq("production"));
    }

    @Test
    void defaultsLabelToProductionWhenBlank() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        when(client.fetchPrompt(any(), eq("sql-analysis"), eq("production")))
                .thenReturn(Optional.of("T {{sql}}"));

        assertThat(provider().resolve(ORG_ID, "sql-analysis", null)).contains("T {{sql}}");
        verify(client).fetchPrompt(any(), eq("sql-analysis"), eq("production"));
    }

    @Test
    void emptyAndNotCachedWhenFetchReturnsEmpty() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        when(client.fetchPrompt(any(), eq("sql-analysis"), eq("production"))).thenReturn(Optional.empty());
        var provider = provider();

        assertThat(provider.resolve(ORG_ID, "sql-analysis", "production")).isEmpty();
        assertThat(provider.resolve(ORG_ID, "sql-analysis", "production")).isEmpty();
        verify(client, times(2)).fetchPrompt(any(), eq("sql-analysis"), eq("production"));
    }

    @Test
    void emptyWhenFetchThrows() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        when(client.fetchPrompt(any(), eq("sql-analysis"), eq("production")))
                .thenThrow(new RuntimeException("404"));

        assertThat(provider().resolve(ORG_ID, "sql-analysis", "production")).isEmpty();
    }

    @Test
    void evictsOrgOnConfigUpdate() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        when(client.fetchPrompt(any(), eq("sql-analysis"), eq("production")))
                .thenReturn(Optional.of("T {{sql}}"));
        var provider = provider();

        provider.resolve(ORG_ID, "sql-analysis", "production");
        provider.onConfigUpdated(new LangfuseConfigUpdatedEvent(ORG_ID));
        provider.resolve(ORG_ID, "sql-analysis", "production");

        verify(client, times(2)).fetchPrompt(any(), eq("sql-analysis"), eq("production"));
    }
}
