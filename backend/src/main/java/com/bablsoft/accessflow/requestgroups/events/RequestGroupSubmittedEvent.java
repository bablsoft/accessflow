package com.bablsoft.accessflow.requestgroups.events;

import java.util.UUID;

/** Published when a group is submitted for AI + review; drives the async per-member analysis. */
public record RequestGroupSubmittedEvent(UUID requestGroupId) {
}
