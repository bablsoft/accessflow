package com.bablsoft.accessflow.mcp.internal.tools.dto;

import java.util.UUID;

public record McpQuerySubmission(UUID queryRequestId, String status) {
}
