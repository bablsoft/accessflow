package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorBaselineEntity;

/**
 * The loaded baseline for one subject: the (possibly not-yet-persisted) entity plus its parsed
 * {@link BaselineProfile}. Detection compares against {@code profile} (the state <em>before</em> the
 * current window is folded in), so the anomalous window never poisons its own comparison.
 */
record BaselineState(BehaviorBaselineEntity entity, BaselineProfile profile) {
}
