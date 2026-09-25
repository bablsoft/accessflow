package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AppliedRowLimit;
import com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowLimitPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowLimitPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRowLimitPolicyResolutionService implements RowLimitPolicyResolutionService {

    private final RowLimitPolicyRepository rowLimitPolicyRepository;
    private final UserRepository userRepository;
    private final UserGroupMembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<AppliedRowLimit> resolve(UUID organizationId, UUID datasourceId,
                                             UUID requesterUserId, Set<String> referencedTables) {
        if (referencedTables == null || referencedTables.isEmpty()) {
            return Optional.empty();
        }
        var policies = rowLimitPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId);
        if (policies.isEmpty()) {
            return Optional.empty();
        }
        var roleName = userRepository.findById(requesterUserId).map(u -> u.roleName()).orElse(null);
        var groupIds = new HashSet<>(membershipRepository.findGroupIdsForUser(requesterUserId));
        Integer lowest = null;
        var winners = new HashSet<UUID>();
        for (var policy : policies) {
            if (!appliesTo(policy, requesterUserId, roleName, groupIds)
                    || referencedTables.stream().noneMatch(ref -> matches(policy, ref))) {
                continue;
            }
            if (lowest == null || policy.getMaxRows() < lowest) {
                lowest = policy.getMaxRows();
                winners.clear();
            }
            if (policy.getMaxRows() == lowest) {
                winners.add(policy.getId());
            }
        }
        return lowest == null ? Optional.empty() : Optional.of(new AppliedRowLimit(lowest, winners));
    }

    /**
     * Lenient on purpose — a match can only lower the cap. The reference equals the policy table
     * (an unqualified reference, or a table name that itself contains dots), or ends in
     * {@code schema.table} / {@code .schema.table} — so a database-qualified
     * {@code db.schema.table} is still caught — or, for a schema-less policy, ends in
     * {@code .table}. The reference is never split on dots.
     */
    static boolean matches(RowLimitPolicyEntity policy, String rawReference) {
        // Engine plugins build their own references; fold case so none can dodge a policy.
        var reference = rawReference.toLowerCase(Locale.ROOT);
        var table = policy.getTableName();
        if (reference.equals(table)) {
            return true;
        }
        var schema = policy.getSchemaName();
        var suffix = schema == null ? table : schema + "." + table;
        return reference.equals(suffix) || reference.endsWith("." + suffix);
    }

    private static boolean appliesTo(RowLimitPolicyEntity policy, UUID userId, String roleName,
                                     Set<UUID> groupIds) {
        return AppliesToMatcher.matches(policy.getAppliesToRoles(), policy.getAppliesToGroupIds(),
                policy.getAppliesToUserIds(), userId, roleName, groupIds);
    }
}
