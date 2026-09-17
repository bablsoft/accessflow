package com.bablsoft.accessflow.serviceaccounts.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolNameTest {

    @Test
    void catalogHoldsTheTwelveAdvertisedToolsWithDistinctWireNames() {
        assertThat(McpToolName.values()).hasSize(12);
        assertThat(Arrays.stream(McpToolName.values()).map(McpToolName::toolName))
                .doesNotHaveDuplicates()
                .allMatch(name -> name.matches("[a-z_]+"));
    }

    @Test
    void wireNamesRoundTrip() {
        for (var tool : McpToolName.values()) {
            assertThat(McpToolName.fromToolName(tool.toolName())).contains(tool);
        }
        assertThat(McpToolName.SUBMIT_QUERY.toolName()).isEqualTo("submit_query");
    }

    @Test
    void unknownOrNullWireNameResolvesToEmpty() {
        assertThat(McpToolName.fromToolName("drop_database")).isEmpty();
        assertThat(McpToolName.fromToolName("SUBMIT_QUERY")).isEmpty(); // enum names are not wire names
        assertThat(McpToolName.fromToolName(null)).isEmpty();
    }
}
