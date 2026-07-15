package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.PermissionGroup;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** The fixed permission catalog, grouped for the admin permission-matrix UI (AF-522). */
public record PermissionCatalogResponse(List<Group> groups) {

    public record Group(String group, List<String> permissions) {
    }

    public static PermissionCatalogResponse catalog() {
        var byGroup = new LinkedHashMap<PermissionGroup, List<String>>();
        for (var group : PermissionGroup.values()) {
            byGroup.put(group, Arrays.stream(Permission.values())
                    .filter(p -> p.group() == group)
                    .map(Permission::name)
                    .toList());
        }
        var groups = byGroup.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> new Group(e.getKey().name(), e.getValue()))
                .toList();
        return new PermissionCatalogResponse(groups);
    }
}
