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
     * (an unqualified reference, or a table name that itself contains dots), equals
     * {@code schema.table}, or — for a schema-less policy — ends in {@code .table}. Never splits the
     * reference on dots, so dotted index or dataset names still compare whole.
     */
    static boolean matches(RowLimitPolicyEntity policy, String reference) {
        var table = policy.getTableName();
        if (reference.equals(table)) {
            return true;
        }
        var schema = policy.getSchemaName();
        if (schema != null) {
            return reference.equals(schema + "." + table);
        }
        return reference.endsWith("." + table);
    }

    private static boolean appliesTo(RowLimitPolicyEntity policy, UUID userId, String roleName,
                                     Set<UUID> groupIds) {
        var roles = policy.getAppliesToRoles();
        var groups = policy.getAppliesToGroupIds();
        var users = policy.getAppliesToUserIds();
        boolean hasRoles = roles != null && roles.length > 0;
        boolean hasGroups = groups != null && groups.length > 0;
        boolean hasUsers = users != null && users.length > 0;
        if (!hasRoles && !hasGroups && !hasUsers) {
            return true; // empty scope = applies to every submitter
        }
        if (hasRoles && roleName != null) {
            for (var allowed : roles) {
                if (allowed != null && roleName.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        if (hasUsers) {
            for (var allowed : users) {
                if (userId.equals(allowed)) {
                    return true;
                }
            }
        }
        if (hasGroups) {
            for (var allowed : groups) {
                if (groupIds.contains(allowed)) {
                    return true;
                }
            }
        }
        return false;
    }
}
