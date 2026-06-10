package com.bablsoft.accessflow.core.api;

public record ResultColumn(String name, int jdbcType, String typeName, boolean restricted) {

    public ResultColumn(String name, int jdbcType, String typeName) {
        this(name, jdbcType, typeName, false);
    }
}
