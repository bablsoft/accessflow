package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.PermissionGroup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogControllerTest {

    private final PermissionCatalogController controller = new PermissionCatalogController();

    @Test
    void catalogCoversEveryPermissionExactlyOnce() {
        var response = controller.catalog();

        var allNames = response.groups().stream()
                .flatMap(g -> g.permissions().stream())
                .toList();
        assertThat(allNames).hasSize(Permission.values().length);
        assertThat(allNames).doesNotHaveDuplicates();
    }

    @Test
    void groupsFollowCatalogGrouping() {
        var response = controller.catalog();

        for (var group : response.groups()) {
            var groupEnum = PermissionGroup.valueOf(group.group());
            for (var name : group.permissions()) {
                assertThat(Permission.valueOf(name).group()).isEqualTo(groupEnum);
            }
        }
    }

    @Test
    void emptyGroupsAreOmitted() {
        var response = controller.catalog();

        assertThat(response.groups()).allSatisfy(g -> assertThat(g.permissions()).isNotEmpty());
    }
}
