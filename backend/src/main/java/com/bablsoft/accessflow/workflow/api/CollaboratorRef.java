package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Lightweight identity of a collaborator (comment author or resolver) for read responses.
 */
public record CollaboratorRef(UUID id, String displayName, String email) {
}
