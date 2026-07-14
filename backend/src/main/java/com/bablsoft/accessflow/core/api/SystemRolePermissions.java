package com.bablsoft.accessflow.core.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative permission sets of the 5 immutable system roles (AF-522), reproducing the
 * pre-custom-roles authorization matrix exactly. Runtime resolution answers system roles from this
 * map; the {@code V114} seed rows mirror it for catalog/UI purposes and a parity test keeps the
 * two in sync.
 */
public final class SystemRolePermissions {

    private static final Map<UserRoleType, Set<Permission>> SETS = buildSets();

    private SystemRolePermissions() {
    }

    /** The permission set of the given system role; never null. */
    public static Set<Permission> of(UserRoleType role) {
        return SETS.get(role);
    }

    private static Map<UserRoleType, Set<Permission>> buildSets() {
        var readonly = EnumSet.of(Permission.QUERY_SUBMIT_SELECT);

        var analyst = EnumSet.copyOf(readonly);
        analyst.add(Permission.QUERY_SUBMIT_DML);

        var reviewer = EnumSet.copyOf(analyst);
        reviewer.add(Permission.QUERY_VIEW_ALL);
        reviewer.add(Permission.QUERY_REVIEW);
        reviewer.add(Permission.ACCESS_REQUEST_REVIEW);
        reviewer.add(Permission.API_REQUEST_REVIEW);
        reviewer.add(Permission.ERASURE_REVIEW);
        reviewer.add(Permission.ATTESTATION_REVIEW);

        var auditor = EnumSet.of(
                Permission.COMPLIANCE_REPORT_VIEW,
                Permission.ATTESTATION_EVIDENCE_EXPORT,
                Permission.BREAK_GLASS_VIEW,
                Permission.ANOMALY_VIEW);

        var admin = EnumSet.allOf(Permission.class);

        Map<UserRoleType, Set<Permission>> sets = new EnumMap<>(UserRoleType.class);
        sets.put(UserRoleType.READONLY, Collections.unmodifiableSet(readonly));
        sets.put(UserRoleType.ANALYST, Collections.unmodifiableSet(analyst));
        sets.put(UserRoleType.REVIEWER, Collections.unmodifiableSet(reviewer));
        sets.put(UserRoleType.AUDITOR, Collections.unmodifiableSet(auditor));
        sets.put(UserRoleType.ADMIN, Collections.unmodifiableSet(admin));
        return Collections.unmodifiableMap(sets);
    }
}
