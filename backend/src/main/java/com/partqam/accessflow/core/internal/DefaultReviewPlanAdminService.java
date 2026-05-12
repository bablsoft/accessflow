package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.CreateReviewPlanCommand;
import com.partqam.accessflow.core.api.IllegalReviewPlanException;
import com.partqam.accessflow.core.api.ReviewPlanAdminService;
import com.partqam.accessflow.core.api.ReviewPlanInUseException;
import com.partqam.accessflow.core.api.ReviewPlanNameAlreadyExistsException;
import com.partqam.accessflow.core.api.ReviewPlanNotFoundException;
import com.partqam.accessflow.core.api.ReviewPlanView;
import com.partqam.accessflow.core.api.UpdateReviewPlanCommand;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
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
class DefaultReviewPlanAdminService implements ReviewPlanAdminService {

    private final ReviewPlanRepository reviewPlanRepository;
    private final ReviewPlanApproverRepository approverRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final DatasourceRepository datasourceRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ReviewPlanView> list(UUID organizationId) {
        return reviewPlanRepository.findAllByOrganization_IdOrderByNameAsc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewPlanView get(UUID id, UUID organizationId) {
        return toView(loadInOrganization(id, organizationId));
    }

    @Override
    @Transactional
    public ReviewPlanView create(CreateReviewPlanCommand command) {
        validateName(command.name());
        if (reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(
                command.organizationId(), command.name())) {
            throw new ReviewPlanNameAlreadyExistsException(command.name());
        }
        var requiresHuman = command.requiresHumanApproval() == null
                || command.requiresHumanApproval();
        validateApprovers(command.approvers(), requiresHuman, command.minApprovalsRequired());

        var entity = new ReviewPlanEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organizationRepository.getReferenceById(command.organizationId()));
        applyCommand(entity,
                command.name(),
                command.description(),
                command.requiresAiReview(),
                command.requiresHumanApproval(),
                command.minApprovalsRequired(),
                command.approvalTimeoutHours(),
                command.autoApproveReads(),
                command.notifyChannels());
        var saved = reviewPlanRepository.save(entity);
        replaceApprovers(saved, command.approvers());
        return toView(saved);
    }

    @Override
    @Transactional
    public ReviewPlanView update(UUID id, UUID organizationId, UpdateReviewPlanCommand command) {
        var entity = loadInOrganization(id, organizationId);
        if (command.name() != null && !command.name().equals(entity.getName())) {
            validateName(command.name());
            if (reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(
                    organizationId, command.name(), id)) {
                throw new ReviewPlanNameAlreadyExistsException(command.name());
            }
            entity.setName(command.name());
        }
        if (command.description() != null) {
            entity.setDescription(command.description());
        }
        if (command.requiresAiReview() != null) {
            entity.setRequiresAiReview(command.requiresAiReview());
        }
        if (command.requiresHumanApproval() != null) {
            entity.setRequiresHumanApproval(command.requiresHumanApproval());
        }
        if (command.minApprovalsRequired() != null) {
            entity.setMinApprovalsRequired(command.minApprovalsRequired());
        }
        if (command.approvalTimeoutHours() != null) {
            entity.setApprovalTimeoutHours(command.approvalTimeoutHours());
        }
        if (command.autoApproveReads() != null) {
            entity.setAutoApproveReads(command.autoApproveReads());
        }
        if (command.notifyChannels() != null) {
            entity.setNotifyChannels(command.notifyChannels().toArray(new String[0]));
        }
        if (command.approvers() != null) {
            validateApprovers(command.approvers(), entity.isRequiresHumanApproval(),
                    entity.getMinApprovalsRequired());
            replaceApprovers(entity, command.approvers());
        } else {
            // Re-validate that the existing approver set still satisfies the (possibly updated)
            // requiresHumanApproval flag.
            if (entity.isRequiresHumanApproval()
                    && approverRepository.findAllByReviewPlan_Id(entity.getId()).isEmpty()) {
                throw new IllegalReviewPlanException(
                        "Review plan requires human approval but has no approvers configured");
            }
        }
        return toView(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        if (datasourceRepository.existsByReviewPlan_Id(entity.getId())) {
            throw new ReviewPlanInUseException(entity.getId());
        }
        approverRepository.deleteAllByReviewPlan_Id(entity.getId());
        reviewPlanRepository.delete(entity);
    }

    private ReviewPlanEntity loadInOrganization(UUID id, UUID organizationId) {
        var entity = reviewPlanRepository.findById(id)
                .orElseThrow(() -> new ReviewPlanNotFoundException(id));
        if (!entity.getOrganization().getId().equals(organizationId)) {
            throw new ReviewPlanNotFoundException(id);
        }
        return entity;
    }

    private void applyCommand(ReviewPlanEntity entity,
                              String name,
                              String description,
                              Boolean requiresAi,
                              Boolean requiresHuman,
                              Integer minApprovals,
                              Integer timeoutHours,
                              Boolean autoApproveReads,
                              List<String> notifyChannels) {
        entity.setName(name);
        entity.setDescription(description);
        if (requiresAi != null) entity.setRequiresAiReview(requiresAi);
        if (requiresHuman != null) entity.setRequiresHumanApproval(requiresHuman);
        if (minApprovals != null) entity.setMinApprovalsRequired(minApprovals);
        if (timeoutHours != null) entity.setApprovalTimeoutHours(timeoutHours);
        if (autoApproveReads != null) entity.setAutoApproveReads(autoApproveReads);
        if (notifyChannels != null) {
            entity.setNotifyChannels(notifyChannels.toArray(new String[0]));
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalReviewPlanException("Review plan name must not be blank");
        }
    }

    private void validateApprovers(List<ReviewPlanView.ApproverRule> approvers,
                                   boolean requiresHumanApproval,
                                   Integer minApprovalsRequired) {
        if (!requiresHumanApproval) {
            return;
        }
        if (approvers == null || approvers.isEmpty()) {
            throw new IllegalReviewPlanException(
                    "At least one approver is required when human approval is enabled");
        }
        for (var rule : approvers) {
            if (rule.userId() == null && rule.role() == null) {
                throw new IllegalReviewPlanException(
                        "Each approver must specify a user or a role");
            }
            if (rule.role() != null && rule.role() != UserRoleType.ADMIN
                    && rule.role() != UserRoleType.REVIEWER) {
                throw new IllegalReviewPlanException(
                        "Approver role must be ADMIN or REVIEWER");
            }
            if (rule.stage() < 1) {
                throw new IllegalReviewPlanException("Approver stage must be at least 1");
            }
        }
        if (minApprovalsRequired != null && minApprovalsRequired > approvers.size()) {
            throw new IllegalReviewPlanException(
                    "min_approvals_required cannot exceed the number of approvers");
        }
    }

    private void replaceApprovers(ReviewPlanEntity plan,
                                  List<ReviewPlanView.ApproverRule> rules) {
        approverRepository.deleteAllByReviewPlan_Id(plan.getId());
        if (rules == null || rules.isEmpty()) {
            return;
        }
        var userIds = new HashSet<UUID>();
        for (var rule : rules) {
            if (rule.userId() != null) {
                userIds.add(rule.userId());
            }
        }
        var users = resolveUsers(userIds, plan.getOrganization().getId());
        for (var rule : rules) {
            var entity = new ReviewPlanApproverEntity();
            entity.setId(UUID.randomUUID());
            entity.setReviewPlan(plan);
            if (rule.userId() != null) {
                var user = users.get(rule.userId());
                if (user == null) {
                    throw new IllegalReviewPlanException(
                            "Approver user not found in organization: " + rule.userId());
                }
                entity.setUser(user);
            }
            entity.setRole(rule.role());
            entity.setStage(rule.stage());
            approverRepository.save(entity);
        }
    }

    private java.util.Map<UUID, UserEntity> resolveUsers(Set<UUID> ids, UUID organizationId) {
        var resolved = new java.util.HashMap<UUID, UserEntity>();
        if (ids.isEmpty()) {
            return resolved;
        }
        for (var id : ids) {
            var user = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalReviewPlanException(
                            "Approver user not found: " + id));
            if (!user.getOrganization().getId().equals(organizationId)) {
                throw new IllegalReviewPlanException(
                        "Approver user does not belong to this organization: " + id);
            }
            resolved.put(id, user);
        }
        return resolved;
    }

    private ReviewPlanView toView(ReviewPlanEntity entity) {
        var approvers = approverRepository.findAllByReviewPlan_IdOrderByStageAsc(entity.getId())
                .stream()
                .map(this::toApproverRule)
                .toList();
        var notifyChannels = entity.getNotifyChannels() == null
                ? List.<String>of()
                : List.of(entity.getNotifyChannels());
        return new ReviewPlanView(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getName(),
                entity.getDescription(),
                entity.isRequiresAiReview(),
                entity.isRequiresHumanApproval(),
                entity.getMinApprovalsRequired(),
                entity.getApprovalTimeoutHours(),
                entity.isAutoApproveReads(),
                new ArrayList<>(notifyChannels),
                approvers,
                entity.getCreatedAt());
    }

    private ReviewPlanView.ApproverRule toApproverRule(ReviewPlanApproverEntity entity) {
        return new ReviewPlanView.ApproverRule(
                entity.getUser() != null ? entity.getUser().getId() : null,
                entity.getRole(),
                entity.getStage());
    }
}
