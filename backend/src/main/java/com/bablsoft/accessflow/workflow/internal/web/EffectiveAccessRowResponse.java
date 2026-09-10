package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.AccessSource;
import com.bablsoft.accessflow.workflow.api.AccessSourceKind;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessRow;
import com.bablsoft.accessflow.workflow.api.TableScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One user's answer in the reverse index (AF-859), with every source that produced it. */
record EffectiveAccessRowResponse(UUID userId, String email, String displayName, String roleName,
                                  boolean granted, TableScope tableScope,
                                  Instant effectiveExpiresAt, boolean canBreakGlass,
                                  List<Source> sources) {

    static EffectiveAccessRowResponse from(EffectiveAccessRow row) {
        return new EffectiveAccessRowResponse(row.userId(), row.email(), row.displayName(),
                row.roleName(), row.granted(), row.tableScope(), row.effectiveExpiresAt(),
                row.canBreakGlass(), row.sources().stream().map(Source::from).toList());
    }

    record Source(AccessSourceKind kind, UUID sourceId, UUID groupId, String groupName,
                  boolean grantsCapability, TableScope tableScope, String coveringAllowListEntry,
                  Instant expiresAt, boolean preApproveQueries) {

        static Source from(AccessSource source) {
            return new Source(source.kind(), source.sourceId(), source.groupId(),
                    source.groupName(), source.grantsCapability(), source.tableScope(),
                    source.coveringAllowListEntry(), source.expiresAt(),
                    source.preApproveQueries());
        }
    }
}
