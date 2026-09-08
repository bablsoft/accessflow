package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatAvailabilityView;

/**
 * Whether the help launcher should be shown at all. Deliberately says nothing about <em>why</em> the
 * agent is off — the provider, the bound configuration and any index error are admin information, and
 * every signed-in user can read this.
 */
record HelpChatAvailabilityResponse(
        boolean enabled,
        boolean retrievalActive,
        String corpusVersion,
        int chunkCount) {

    static HelpChatAvailabilityResponse from(HelpChatAvailabilityView view) {
        return new HelpChatAvailabilityResponse(view.enabled(), view.retrievalActive(),
                view.corpusVersion(), view.chunkCount());
    }
}
