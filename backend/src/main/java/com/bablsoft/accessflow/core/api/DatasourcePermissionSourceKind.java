package com.bablsoft.accessflow.core.api;

/** Where one contribution to an effective datasource permission came from (issue AF-859). */
public enum DatasourcePermissionSourceKind {

    /** A {@code datasource_user_permissions} row granted to the user directly. */
    DIRECT,

    /** A {@code datasource_group_permissions} row inherited through group membership (AF-530). */
    GROUP
}
