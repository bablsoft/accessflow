package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * A colleague the caller may name as their delegate (#622) — deliberately just enough to render a
 * picker.
 *
 * <p>This is a narrow, intentional disclosure: the only other user listing is gated on
 * {@code USER_MANAGE}, which most reviewers do not hold, so without it the feature is unusable by
 * exactly the people it is for. It carries no role, permission, or activity information, and within
 * an organization reviewers already see each other's names on approval timelines and review queues.
 */
public record ReviewDelegateCandidate(UUID id, String email, String displayName) {
}
