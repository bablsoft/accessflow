package com.bablsoft.accessflow.core.api;

/**
 * The kinds of resource a review delegation can be narrowed to (#622).
 *
 * <p>Grouped requests are deliberately absent: a group is a bundle of members of these kinds, so it
 * is covered when the delegation's scope matches one of its members (or when the delegation is
 * unrestricted). See {@code requestgroups} for that fold.
 */
public enum DelegationScopeKind {
    DATASOURCE,
    API_CONNECTOR
}
