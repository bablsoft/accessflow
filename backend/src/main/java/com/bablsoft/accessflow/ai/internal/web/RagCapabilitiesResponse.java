package com.bablsoft.accessflow.ai.internal.web;

/**
 * Deployment-level RAG capabilities the admin UI needs to render correctly (AF-336). Currently only
 * whether the in-app pgvector store is usable; serialized as {@code pgvector_available}.
 */
record RagCapabilitiesResponse(boolean pgvectorAvailable) {
}
