package com.bablsoft.accessflow.requestgroups.api;

import java.util.UUID;

public record RequestGroupSubmissionResult(UUID id, RequestGroupStatus status) {
}
