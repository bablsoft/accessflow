package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.AccessSource;
import com.bablsoft.accessflow.workflow.api.AccessSourceKind;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessQuery;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessRow;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessService;
import com.bablsoft.accessflow.workflow.api.InvalidEffectiveAccessQueryException;
import com.bablsoft.accessflow.workflow.api.StatementCapability;
import com.bablsoft.accessflow.workflow.api.TableScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who can reach one table (issue AF-859).
 *
 * <p>Reads every contribution on the datasource in a fixed number of queries and merges each user's
 * own set through {@code core}'s merge, rather than asking per user — the merge is the part that
 * must agree with enforcement, so it is borrowed rather than repeated. Table matching likewise goes
 * through {@link DatasourcePermissionChecker}, the same static the submission gate calls.
 */
@Service
@RequiredArgsConstructor
class DefaultEffectiveAccessService implements EffectiveAccessService {

    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final RolePermissionHolderLookupService rolePermissionHolderLookupService;
    private final AccessGrantLookupService accessGrantLookupService;
    private final UserQueryService userQueryService;

    @Override
    public PageResponse<EffectiveAccessRow> report(UUID organizationId, EffectiveAccessQuery query,
                                                   PageRequest pageRequest) {
        // Existence and org scoping first: a datasource in another organization is 404, never 403.
        datasourceAdminService.getForAdmin(query.datasourceId(), organizationId);
        var table = normalizeTable(query.table());
        var queryType = queryTypeFor(query.capability());

        var byUser = new LinkedHashMap<UUID, List<DatasourcePermissionContribution>>();
        for (var contribution : permissionLookupService
                .findContributionsForDatasource(query.datasourceId())) {
            byUser.computeIfAbsent(contribution.userId(), k -> new ArrayList<>())
                    .add(contribution);
        }
        var queryAdminIds = Set.copyOf(rolePermissionHolderLookupService
                .findUserIdsWithPermission(organizationId, Permission.QUERY_ADMIN));
        var preApprovingGrantIds = accessGrantLookupService
                .findPreApprovingGrantsForDatasource(organizationId, query.datasourceId()).stream()
                .map(AccessGrantView::id)
                .collect(Collectors.toUnmodifiableSet());

        var candidateIds = new LinkedHashSet<>(byUser.keySet());
        candidateIds.addAll(queryAdminIds);
        var rows = userQueryService.findByIds(List.copyOf(candidateIds)).stream()
                .filter(UserView::active)
                .filter(user -> organizationId.equals(user.organizationId()))
                .map(user -> toRow(user, byUser.getOrDefault(user.id(), List.of()),
                        queryAdminIds.contains(user.id()), preApprovingGrantIds, table,
                        queryType))
                .filter(EffectiveAccessRow::granted)
                .sorted(Comparator.comparing(EffectiveAccessRow::email,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
        return slice(rows, pageRequest);
    }

    /**
     * The query parameter arrives raw, unlike the parser output the gate normally feeds the checker
     * — so it goes through the same {@code normalizeList} an allow-list entry does before either
     * side is compared.
     */
    private static String normalizeTable(String table) {
        var normalized = table == null
                ? List.<String>of()
                : DatasourcePermissionChecker.normalizeList(List.of(table));
        if (normalized.isEmpty()) {
            // A spelling that normalizes to nothing would match every allow-list entry vacuously and
            // report the whole organization as able to reach "" — a refusal is the only honest answer.
            throw new InvalidEffectiveAccessQueryException("Table normalizes to an empty name");
        }
        return normalized.get(0);
    }

    private static QueryType queryTypeFor(StatementCapability capability) {
        return switch (capability) {
            case READ -> QueryType.SELECT;
            // Every DML verb resolves to can_write, so any one of them stands for the class.
            case WRITE -> QueryType.UPDATE;
            case DDL -> QueryType.DDL;
        };
    }

    private EffectiveAccessRow toRow(UserView user,
                                     List<DatasourcePermissionContribution> contributions,
                                     boolean queryAdmin, Set<UUID> preApprovingGrantIds,
                                     String table, QueryType queryType) {
        var merged = permissionLookupService.mergeContributions(contributions).orElse(null);
        var sources = new ArrayList<AccessSource>(contributions.size() + 2);
        if (queryAdmin) {
            sources.add(new AccessSource(AccessSourceKind.QUERY_ADMIN_BYPASS, null, null, null,
                    true, TableScope.ALL_TABLES, null, null, false));
        }
        for (var contribution : contributions) {
            sources.add(toSource(contribution, preApprovingGrantIds, table, queryType));
        }
        // One per granting row rather than one derived from the merge: "who gave me this" is the
        // useful half of a break-glass answer, and the merge does not keep it.
        for (var contribution : contributions) {
            if (contribution.canBreakGlass()) {
                sources.add(new AccessSource(AccessSourceKind.BREAK_GLASS, contribution.sourceId(),
                        contribution.groupId(), contribution.groupName(), false,
                        scopeOf(contribution.allowedSchemas(), contribution.allowedTables()),
                        coveringEntry(contribution.allowedSchemas(), contribution.allowedTables(),
                                table),
                        contribution.expiresAt(), false));
            }
        }
        boolean granted = queryAdmin || grants(merged, table, queryType);
        var scope = queryAdmin || merged == null
                ? TableScope.ALL_TABLES
                : scopeOf(merged.allowedSchemas(), merged.allowedTables());
        return new EffectiveAccessRow(user.id(), user.email(), user.displayName(), user.roleName(),
                granted, scope, queryAdmin ? null : expiresAt(merged),
                merged != null && merged.canBreakGlass(), sources);
    }

    private static AccessSource toSource(DatasourcePermissionContribution contribution,
                                         Set<UUID> preApprovingGrantIds, String table,
                                         QueryType queryType) {
        boolean direct = contribution.sourceKind() == DatasourcePermissionSourceKind.DIRECT;
        // The originating request is a foreign key on the row (#969); whether that grant is still
        // active and pre-approving is read from the access module's own active set, so an expired
        // or never-opted-in grant keeps the label and loses only the pre-approval.
        boolean jit = direct && contribution.accessGrantRequestId() != null;
        boolean preApproveQueries = jit
                && preApprovingGrantIds.contains(contribution.accessGrantRequestId());
        var kind = jit ? AccessSourceKind.JIT_GRANT
                : direct ? AccessSourceKind.DIRECT_PERMISSION : AccessSourceKind.GROUP_PERMISSION;
        return new AccessSource(kind, contribution.sourceId(), contribution.groupId(),
                contribution.groupName(),
                DatasourcePermissionChecker.hasCapability(contribution.canRead(),
                        contribution.canWrite(), contribution.canDdl(), queryType),
                scopeOf(contribution.allowedSchemas(), contribution.allowedTables()),
                coveringEntry(contribution.allowedSchemas(), contribution.allowedTables(), table),
                contribution.expiresAt(), preApproveQueries);
    }

    /** Both the capability and the table coverage are read off the merged permission, never a part. */
    private static boolean grants(DatasourceUserPermissionView merged, String table,
                                  QueryType queryType) {
        if (merged == null) {
            return false;
        }
        if (!DatasourcePermissionChecker.hasCapability(merged, queryType)) {
            return false;
        }
        return DatasourcePermissionChecker.rejectedTables(merged, Set.of(table)).isEmpty();
    }

    private static TableScope scopeOf(List<String> allowedSchemas, List<String> allowedTables) {
        return unrestricted(allowedSchemas, allowedTables)
                ? TableScope.ALL_TABLES
                : TableScope.ALLOW_LISTED;
    }

    private static String coveringEntry(List<String> allowedSchemas, List<String> allowedTables,
                                        String table) {
        if (unrestricted(allowedSchemas, allowedTables)) {
            return null;
        }
        return DatasourcePermissionChecker.coveringEntry(
                DatasourcePermissionChecker.normalizeList(allowedSchemas),
                DatasourcePermissionChecker.normalizeList(allowedTables), table);
    }

    private static boolean unrestricted(List<String> allowedSchemas, List<String> allowedTables) {
        return DatasourcePermissionChecker.normalizeList(allowedSchemas).isEmpty()
                && DatasourcePermissionChecker.normalizeList(allowedTables).isEmpty();
    }

    private static Instant expiresAt(DatasourceUserPermissionView merged) {
        return merged == null ? null : merged.expiresAt();
    }

    /**
     * Sliced in memory, and deliberately so: {@code granted} is a Java computation over merged
     * multi-row state plus set membership in the {@code QUERY_ADMIN} holder list, which no
     * {@code Specification} expresses. Paging in the database would filter after the fetch and
     * report a total that is simply wrong. The candidate set is bounded by the users holding any
     * permission on one datasource plus the organization's admins, both capped by its user quota.
     */
    private static PageResponse<EffectiveAccessRow> slice(List<EffectiveAccessRow> rows,
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
