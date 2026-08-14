package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * An offset-based page (#621). Unlike {@link PageResponse}, the window starts at an arbitrary
 * zero-based {@code offset} rather than a page boundary — SCIM's {@code startIndex} is 1-based
 * and not required to be page-aligned.
 */
public record DirectoryPage<T>(List<T> content, long totalResults) {
}
