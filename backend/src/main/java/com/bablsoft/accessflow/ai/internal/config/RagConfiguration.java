package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the {@link RagProperties} binding for the RAG knowledge base (AF-336). The pgvector /
 * Qdrant Spring AI vector stores are built per {@code ai_config} row at runtime by
 * {@code SpringAiVectorStoreFactory}, not as application-context beans — the matching auto-configs
 * are excluded in {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
class RagConfiguration {
}
