package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ExportPolicyResolutionService;
import com.bablsoft.accessflow.core.api.ExportPolicyView;
import com.bablsoft.accessflow.core.internal.persistence.entity.ExportPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ExportPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultExportPolicyResolutionService implements ExportPolicyResolutionService {

    private final ExportPolicyRepository exportPolicyRepository;
    private final UserRepository userRepository;
    private final UserGroupMembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ExportPolicyView> resolveApplicable(UUID organizationId, UUID datasourceId,
                                                    UUID requesterUserId) {
        var policies = exportPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId);
        if (policies.isEmpty()) {
            return List.of();
        }
        var user = userRepository.findById(requesterUserId).orElse(null);
        var roleName = user != null ? user.roleName() : null;
        var groupIds = new HashSet<>(membershipRepository.findGroupIdsForUser(requesterUserId));
        var applicable = new ArrayList<ExportPolicyView>();
        for (var policy : policies) {
            if (appliesTo(policy, requesterUserId, roleName, groupIds)) {
                applicable.add(toView(policy));
            }
        }
        return applicable;
    }

    private static boolean appliesTo(ExportPolicyEntity policy, UUID userId, String roleName,
                                     Set<UUID> groupIds) {
        boolean hasRoles = policy.getAppliesToRoles() != null && policy.getAppliesToRoles().length > 0;
        boolean hasGroups =
                policy.getAppliesToGroupIds() != null && policy.getAppliesToGroupIds().length > 0;
        boolean hasUsers =
                policy.getAppliesToUserIds() != null && policy.getAppliesToUserIds().length > 0;
        if (!hasRoles && !hasGroups && !hasUsers) {
            return true; // empty scope = applies to every exporter (governance-safe default)
        }
        if (hasRoles && roleName != null) {
            for (var allowed : policy.getAppliesToRoles()) {
                if (allowed != null && roleName.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        if (hasUsers) {
            for (var allowed : policy.getAppliesToUserIds()) {
                if (userId.equals(allowed)) {
                    return true;
                }
            }
        }
        if (hasGroups && !groupIds.isEmpty()) {
            for (var allowed : policy.getAppliesToGroupIds()) {
                if (groupIds.contains(allowed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ExportPolicyView toView(ExportPolicyEntity entity) {
        return new ExportPolicyView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getMode(),
                entity.getRowCap(),
                DefaultExportPolicyAdminService.toClassificationList(entity.getDenyClassifications()),
                entity.getAppliesToRoles() == null ? List.of() : List.of(entity.getAppliesToRoles()),
                entity.getAppliesToGroupIds() == null ? List.of()
                        : List.of(entity.getAppliesToGroupIds()),
                entity.getAppliesToUserIds() == null ? List.of()
                        : List.of(entity.getAppliesToUserIds()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
