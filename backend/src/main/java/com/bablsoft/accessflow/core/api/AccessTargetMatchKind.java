package com.bablsoft.accessflow.core.api;

/** Why a role/group/user-scoped policy targets a user (#946). */
public enum AccessTargetMatchKind {
    /** The policy's scope lists are all empty — it targets every user. */
    EVERYONE,
    ROLE,
    GROUP,
    USER
}
