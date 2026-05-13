package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.OAuth2ProviderSummaryView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;

record OAuth2ProviderSummaryResponse(
        OAuth2ProviderType provider,
        String displayName) {

    static OAuth2ProviderSummaryResponse from(OAuth2ProviderSummaryView view) {
        return new OAuth2ProviderSummaryResponse(view.provider(), view.displayName());
    }
}
