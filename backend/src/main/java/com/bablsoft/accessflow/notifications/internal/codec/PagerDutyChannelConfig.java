package com.bablsoft.accessflow.notifications.internal.codec;

import java.util.Set;

public record PagerDutyChannelConfig(
        String routingKeyPlain,
        PagerDutySeverity defaultSeverity,
        Set<PagerDutyTrigger> triggers) {
}
