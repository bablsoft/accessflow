package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.serviceaccounts.api.McpToolName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link McpToolName} (the allow-list catalog in {@code serviceaccounts.api}, #868) equal to
 * the set of {@code @Tool} names the MCP server actually registers. The tool objects are found by
 * scanning the {@code mcp} module for {@code @Service} classes rather than by naming them, so a
 * fourth tool class added to {@code McpServerConfiguration} is covered without touching this test;
 * a tool added or renamed without its enum value fails the build here rather than silently
 * becoming un-allow-listable (#872).
 */
class McpToolNameParityTest {

    @Test
    void everyRegisteredToolHasExactlyOneCatalogValue() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        Set<Class<?>> toolClasses = scanner.findCandidateComponents("com.bablsoft.accessflow.mcp").stream()
                .<Class<?>>map(bean -> ClassUtils.resolveClassName(bean.getBeanClassName(), getClass().getClassLoader()))
                .filter(type -> Arrays.stream(type.getDeclaredMethods())
                        .anyMatch(method -> method.isAnnotationPresent(Tool.class)))
                .collect(Collectors.toSet());
        assertThat(toolClasses)
                .as("the three tool objects McpServerConfiguration registers")
                .contains(McpToolService.class, McpReviewToolService.class, McpDataToolService.class);

        var registered = toolClasses.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(tool -> tool != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        var catalog = Arrays.stream(McpToolName.values())
                .map(McpToolName::toolName)
                .collect(Collectors.toSet());

        assertThat(catalog).isEqualTo(Set.copyOf(registered));
    }
}
