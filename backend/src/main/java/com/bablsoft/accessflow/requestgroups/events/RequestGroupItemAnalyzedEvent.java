package com.bablsoft.accessflow.requestgroups.events;

import java.util.UUID;

/**
 * Published after a single member finishes AI analysis (or its analysis failed and escalated). The
 * group-state listener recomputes the aggregate risk (max) and, once all members are analyzed, runs
 * the group's review-plan resolution.
 */
public record RequestGroupItemAnalyzedEvent(UUID requestGroupId, UUID itemId) {
}
