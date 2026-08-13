package com.bablsoft.accessflow.core.api;

public enum UserGroupMembershipSourceType {
    MANUAL,
    IDP,
    /** Pushed by an identity provider over SCIM 2.0 (#621). */
    SCIM
}
