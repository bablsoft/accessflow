package com.bablsoft.accessflow.security.api;

public record OAuth2ProviderSummaryView(
        OAuth2ProviderType provider,
        String displayName) {
}
