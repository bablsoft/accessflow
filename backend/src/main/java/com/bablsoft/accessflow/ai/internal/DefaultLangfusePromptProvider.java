package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.LangfuseProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Fetches Langfuse-managed prompts at render time, caching successful lookups for
 * {@code accessflow.langfuse.prompt-cache-ttl} so edits in Langfuse propagate without a restart.
 * Failures and empties are never cached (they fall back to the local template) and the whole org is
 * evicted on {@link LangfuseConfigUpdatedEvent}.
 */
@Component
class DefaultLangfusePromptProvider implements LangfusePromptProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultLangfusePromptProvider.class);

    private final LangfuseConfigResolver configResolver;
    private final LangfuseClient client;
    private final Cache<CacheKey, String> cache;

    DefaultLangfusePromptProvider(LangfuseConfigResolver configResolver, LangfuseClient client,
                                  LangfuseProperties properties) {
        this.configResolver = configResolver;
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.promptCacheTtl())
                .maximumSize(1_000)
                .build();
    }

    @Override
    public Optional<String> resolve(UUID organizationId, String promptName, String promptLabel) {
        if (organizationId == null || promptName == null || promptName.isBlank()) {
            return Optional.empty();
        }
        var resolved = configResolver.resolve(organizationId).orElse(null);
        if (resolved == null || !resolved.promptManagementEnabled()) {
            return Optional.empty();
        }
        var label = (promptLabel == null || promptLabel.isBlank()) ? "production" : promptLabel;
        var key = new CacheKey(organizationId, promptName, label);
        var cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            var fetched = client.fetchPrompt(resolved, promptName, label);
            fetched.ifPresent(template -> cache.put(key, template));
            return fetched;
        } catch (RuntimeException e) {
            log.warn("Langfuse prompt fetch failed for org={} name={} label={}: {}",
                    organizationId, promptName, label, e.getMessage());
            return Optional.empty();
        }
    }

    @ApplicationModuleListener
    void onConfigUpdated(LangfuseConfigUpdatedEvent event) {
        cache.asMap().keySet().removeIf(key -> key.organizationId().equals(event.organizationId()));
    }

    private record CacheKey(UUID organizationId, String promptName, String promptLabel) {
    }
}
