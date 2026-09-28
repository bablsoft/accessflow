package com.bablsoft.accessflow.core.api;

/**
 * One reason a scoped policy targets (or, for masking, reveals to) a user (#946). {@code ref} is
 * the matched role name, group id or user id; {@code null} for {@link AccessTargetMatchKind#EVERYONE}.
 */
public record AccessTargetMatch(AccessTargetMatchKind kind, String ref) {

    public static final AccessTargetMatch EVERYONE = new AccessTargetMatch(
            AccessTargetMatchKind.EVERYONE, null);
}
