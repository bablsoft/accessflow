package com.partqam.accessflow.core.api;

import java.util.UUID;

/**
 * Lightweight cross-module DTO carrying a datasource's id and display name. Used when other
 * modules need to surface datasource references in error responses (e.g. an AI config delete
 * blocked because datasources still bind to it) without depending on {@code core.internal}.
 */
public record DatasourceRef(UUID id, String name) {
}
