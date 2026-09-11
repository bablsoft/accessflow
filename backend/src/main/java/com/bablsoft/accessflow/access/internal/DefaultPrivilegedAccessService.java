package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.BreakGlassGrant;
import com.bablsoft.accessflow.access.api.PrivilegedAccessEvidence;
import com.bablsoft.accessflow.access.api.PrivilegedAccessQuery;
import com.bablsoft.accessflow.access.api.PrivilegedAccessRow;
import com.bablsoft.accessflow.access.api.PrivilegedAccessService;
import com.bablsoft.accessflow.access.api.QueryAdminBypass;
import com.bablsoft.accessflow.access.api.StandingBypassKind;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QuerySubmitterEvidenceLookupService;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The privileged-access report (#968): the org-wide answer to "who can reach data without a
 * permission row". Two sources, both read from the same lookups enforcement uses:
 *
 * <ul>
 *   <li>{@code QUERY_ADMIN} holders — {@link RolePermissionHolderLookupService} inverts the role
 *       catalog, so the system {@code ADMIN} role and a custom role carrying the permission
 *       (AF-522) resolve identically. Such a user skips the per-datasource gate in the submission
 *       service outright, which is why no permission screen shows them.</li>
 *   <li>Break-glass grantees — every unexpired {@code can_break_glass} contribution in the
 *       organization, direct or inherited through a group, on an <em>active</em> datasource. A
 *       grant on an inactive datasource is omitted: the datasource gate fails closed before the
 *       grant is ever consulted, and re-activating the datasource re-arms it.</li>
 * </ul>
 *
 * <p>Evidence is a direct aggregate over {@code query_requests} rather than the #625
 * {@code grant_usage_summary}: that fold is keyed per grant and drops events that match no live
 * grant — which is exactly every query a {@code QUERY_ADMIN} holder submits with no permission row.
 */
@Service
@RequiredArgsConstructor
class DefaultPrivilegedAccessService implements PrivilegedAccessService {

    private static final Comparator<BreakGlassGrant> GRANT_ORDER = Comparator
            .comparing(BreakGlassGrant::datasourceName, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(g -> g.sourceKind() == DatasourcePermissionSourceKind.DIRECT ? 0 : 1);

    private final RolePermissionHolderLookupService rolePermissionHolderLookupService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final UserQueryService userQueryService;
    private final DatasourceLookupService datasourceLookupService;
    private final QuerySubmitterEvidenceLookupService evidenceLookupService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PrivilegedAccessRow> report(UUID organizationId, PrivilegedAccessQuery query,
                                                    PageRequest pageRequest) {
        var filter = query == null ? PrivilegedAccessQuery.empty() : query;
        var queryAdminIds = new HashSet<>(
                rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                        Permission.QUERY_ADMIN));
        var grantsByUser = breakGlassGrantsByUser(organizationId);

        var candidateIds = new HashSet<>(queryAdminIds);
        candidateIds.addAll(grantsByUser.keySet());
        if (filter.userId() != null) {
            // Narrowing before the user fetch: an id outside the candidate set is simply an empty page.
            candidateIds.retainAll(Set.of(filter.userId()));
        }
        if (candidateIds.isEmpty()) {
            return slice(List.of(), pageRequest);
        }

        // The holder lookup is already active-only and org-scoped; the break-glass rows are not,
        // so filter both here rather than trust one side.
        var users = userQueryService.findByIds(candidateIds).stream()
                .filter(UserView::active)
                .filter(u -> organizationId.equals(u.organizationId()))
                .toList();
        var evidence = evidenceLookupService.findBySubmitters(organizationId,
                users.stream().map(UserView::id).toList());

        var rows = new ArrayList<PrivilegedAccessRow>();
        for (var user : users) {
            var row = toRow(user, queryAdminIds.contains(user.id()),
                    grantsByUser.getOrDefault(user.id(), List.of()),
                    PrivilegedAccessEvidence.from(evidence.get(user.id())));
            if (filter.kind() == null || row.bypassKinds().contains(filter.kind())) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(PrivilegedAccessRow::email,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return slice(rows, pageRequest);
    }

    private Map<UUID, List<BreakGlassGrant>> breakGlassGrantsByUser(UUID organizationId) {
        var activeDatasources = datasourceLookupService.findActiveRefsByOrganization(organizationId)
                .stream()
                .collect(Collectors.toMap(DatasourceRef::id, Function.identity(), (a, b) -> a));
        var byUser = new LinkedHashMap<UUID, List<BreakGlassGrant>>();
        for (var contribution : permissionLookupService
                .findBreakGlassContributionsForOrganization(organizationId)) {
            var datasource = activeDatasources.get(contribution.datasourceId());
            if (datasource == null) {
                continue;
            }
            byUser.computeIfAbsent(contribution.userId(), k -> new ArrayList<>())
                    .add(toGrant(contribution, datasource));
        }
        byUser.values().forEach(grants -> grants.sort(GRANT_ORDER));
        return byUser;
    }

    private static PrivilegedAccessRow toRow(UserView user, boolean queryAdmin,
                                             List<BreakGlassGrant> grants,
                                             PrivilegedAccessEvidence evidence) {
        // The legacy enum column is populated for the five system roles and NULL on a custom role
        // (roleRef is the source of truth, kept in sync) — so its presence is the system/custom flag.
        boolean systemRole = user.role() != null;
        var kinds = EnumSet.noneOf(StandingBypassKind.class);
        if (queryAdmin) {
            kinds.add(StandingBypassKind.QUERY_ADMIN);
        }
        if (!grants.isEmpty()) {
            kinds.add(StandingBypassKind.BREAK_GLASS);
        }
        return new PrivilegedAccessRow(
                user.id(),
                user.email(),
                user.displayName(),
                user.roleId(),
                user.roleName(),
                systemRole,
                kinds,
                queryAdmin ? new QueryAdminBypass(user.roleId(), user.roleName(), systemRole) : null,
                grants,
                evidence);
    }

    private static BreakGlassGrant toGrant(DatasourcePermissionContribution c, DatasourceRef datasource) {
        return new BreakGlassGrant(datasource.id(), datasource.name(), c.sourceKind(), c.sourceId(),
                c.groupId(), c.groupName(), c.expiresAt());
    }

    /**
     * Sliced in memory, and deliberately so: a row is a Java merge of three lookups (role
     * holders, break-glass contributions, evidence) that no {@code Specification} expresses, and
     * paging in the database would report a total that is simply wrong. The candidate set is the
     * organization's {@code QUERY_ADMIN} holders plus its break-glass grantees, capped by its user
     * quota.
     */
    private static PageResponse<PrivilegedAccessRow> slice(List<PrivilegedAccessRow> rows,
                                                           PageRequest pageRequest) {
        int page = pageRequest == null ? 0 : pageRequest.page();
        // PageRequest's own constructor rejects a non-positive size, so the only zero to guard
        // against is the unpaged "everything" case over an empty result set.
        int size = pageRequest == null ? Math.max(rows.size(), 1) : pageRequest.size();
        int from = Math.min((int) Math.min((long) page * size, Integer.MAX_VALUE), rows.size());
        int to = Math.min(from + size, rows.size());
        int totalPages = (int) Math.ceil(rows.size() / (double) size);
        return new PageResponse<>(rows.subList(from, to), page, size, rows.size(), totalPages);
    }
}
