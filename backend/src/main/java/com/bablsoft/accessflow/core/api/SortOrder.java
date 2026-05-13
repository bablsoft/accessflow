package com.bablsoft.accessflow.core.api;

import java.util.Objects;

public record SortOrder(String property, Direction direction) {

    public SortOrder {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(direction, "direction");
        if (property.isBlank()) {
            throw new IllegalArgumentException("property must not be blank");
        }
    }

    public static SortOrder asc(String property) {
        return new SortOrder(property, Direction.ASC);
    }

    public static SortOrder desc(String property) {
        return new SortOrder(property, Direction.DESC);
    }

    public enum Direction { ASC, DESC }
}
