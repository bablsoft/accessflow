package com.bablsoft.accessflow.security.internal.web.model;

import java.util.List;

/**
 * Response of {@code GET /api/v1/datasources/secret-providers} (AF-448): the external
 * secret-store provider ids enabled in this deployment, in stable order
 * ({@code vault}, {@code aws}, {@code azure}).
 */
public record SecretProvidersResponse(List<String> providers) {
}
