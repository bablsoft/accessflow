package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateUserGroupCommand;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UpdateUserGroupCommand;
import com.bablsoft.accessflow.core.api.UserGroupMembershipNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupMembershipSourceType;
import com.bablsoft.accessflow.core.api.UserGroupMembershipView;
import com.bablsoft.accessflow.core.api.UserGroupNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipSource;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceReviewerRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class DefaultUserGroupService implements UserGroupService {

    private final UserGroupRepository userGroupRepository;
    private final UserGroupMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final DatasourceReviewerRepository datasourceReviewerRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserGroupView> listGroups(UUID organizationId, PageRequest pageRequest) {
        var page = userGroupRepository.findAllByOrganization_Id(organizationId,
                PageAdapter.toSpringPageable(pageRequest));
        var counts = countMembersByGroupIds(
                page.getContent().stream().map(UserGroupEntity::getId).toList());
        return PageAdapter.toPageResponse(page.map(entity -> toView(entity,
                counts.getOrDefault(entity.getId(), 0L))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserGroupView> listAll(UUID organizationId) {
        var page = userGroupRepository.findAllByOrganization_Id(organizationId,
                org.springframework.data.domain.Pageable.unpaged());
        var ids = page.getContent().stream().map(UserGroupEntity::getId).toList();
        var counts = countMembersByGroupIds(ids);
        return page.getContent().stream()
                .map(entity -> toView(entity, counts.getOrDefault(entity.getId(), 0L)))
                .sorted(Comparator.comparing(UserGroupView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UserGroupView getGroup(UUID groupId, UUID organizationId) {
        var entity = loadInOrganization(groupId, organizationId);
        return toView(entity, membershipRepository.countByGroup_Id(groupId));
    }

    @Override
    @Transactional
    public UserGroupView createGroup(CreateUserGroupCommand command) {
        var normalizedName = command.name() == null ? null : command.name().trim();
        userGroupRepository.findByOrganizationIdAndNameIgnoreCase(command.organizationId(),
                        normalizedName)
                .ifPresent(existing -> {
                    throw new UserGroupNameAlreadyExistsException(existing.getName());
                });
        var organization = organizationRepository.getReferenceById(command.organizationId());
        var entity = new UserGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setName(normalizedName);
        entity.setDescription(command.description());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        return toView(userGroupRepository.save(entity), 0L);
    }

    @Override
    @Transactional
    public UserGroupView updateGroup(UUID groupId, UUID organizationId,
                                     UpdateUserGroupCommand command) {
        var entity = loadInOrganization(groupId, organizationId);
        if (command.name() != null) {
            var trimmed = command.name().trim();
            if (!trimmed.equalsIgnoreCase(entity.getName())) {
                userGroupRepository.findByOrganizationIdAndNameIgnoreCase(organizationId, trimmed)
                        .filter(other -> !other.getId().equals(groupId))
                        .ifPresent(other -> {
                            throw new UserGroupNameAlreadyExistsException(other.getName());
                        });
            }
            entity.setName(trimmed);
        }
        if (command.description() != null) {
            entity.setDescription(command.description());
        }
        entity.setUpdatedAt(Instant.now());
        return toView(entity, membershipRepository.countByGroup_Id(groupId));
    }

    @Override
    @Transactional
    public void deleteGroup(UUID groupId, UUID organizationId) {
        var entity = loadInOrganization(groupId, organizationId);
        datasourceReviewerRepository.deleteByGroupId(entity.getId());
        membershipRepository.deleteByGroupId(entity.getId());
        userGroupRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserGroupMembershipView> listMembers(UUID groupId, UUID organizationId) {
        loadInOrganization(groupId, organizationId);
        return membershipRepository.findAllByGroup_Id(groupId).stream()
                .map(DefaultUserGroupService::toMembershipView)
                .sorted(Comparator.comparing(UserGroupMembershipView::userEmail,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Override
    @Transactional
    public UserGroupMembershipView addMember(UUID groupId, UUID userId, UUID organizationId) {
        var group = loadInOrganization(groupId, organizationId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!user.getOrganization().getId().equals(organizationId)) {
            throw new UserNotFoundException(userId);
        }
        if (membershipRepository.existsByUser_IdAndGroup_Id(userId, groupId)) {
            return membershipRepository.findAllByGroup_Id(groupId).stream()
                    .filter(m -> m.getUser().getId().equals(userId))
                    .findFirst()
                    .map(DefaultUserGroupService::toMembershipView)
                    .orElseThrow();
        }
        var membership = new UserGroupMembershipEntity();
        membership.setId(new UserGroupMembershipEntity.Id(userId, groupId));
        membership.setUser(user);
        membership.setGroup(group);
        membership.setSource(UserGroupMembershipSource.MANUAL);
        membership.setJoinedAt(Instant.now());
        return toMembershipView(membershipRepository.save(membership));
    }

    @Override
    @Transactional
    public void removeMember(UUID groupId, UUID userId, UUID organizationId) {
        loadInOrganization(groupId, organizationId);
        if (!membershipRepository.existsByUser_IdAndGroup_Id(userId, groupId)) {
            throw new UserGroupMembershipNotFoundException(groupId, userId);
        }
        membershipRepository.deleteByUserIdAndGroupId(userId, groupId);
    }

    @Override
    @Transactional
    public Set<UUID> syncIdpMemberships(UUID userId, UUID organizationId,
                                        Collection<UUID> desiredGroupIds) {
        var existing = membershipRepository.findAllByUser_Id(userId);
        var desired = desiredGroupIds == null ? Set.<UUID>of() : new LinkedHashSet<>(desiredGroupIds);
        var idpExistingByGroup = existing.stream()
                .filter(m -> m.getSource() == UserGroupMembershipSource.IDP)
                .collect(Collectors.toMap(m -> m.getGroup().getId(), m -> m));
        var manualGroupIds = existing.stream()
                .filter(m -> m.getSource() == UserGroupMembershipSource.MANUAL)
                .map(m -> m.getGroup().getId())
                .collect(Collectors.toSet());

        // Remove IDP rows no longer in desired set.
        for (var entry : idpExistingByGroup.entrySet()) {
            if (!desired.contains(entry.getKey())) {
                membershipRepository.delete(entry.getValue());
            }
        }

        // Add new desired rows (skip already-manual rows — a manual membership wins).
        for (UUID groupId : desired) {
            if (manualGroupIds.contains(groupId) || idpExistingByGroup.containsKey(groupId)) {
                continue;
            }
            var group = userGroupRepository.findById(groupId).orElse(null);
            if (group == null
                    || !group.getOrganization().getId().equals(organizationId)) {
                continue;
            }
            var user = userRepository.findById(userId).orElseThrow(
                    () -> new UserNotFoundException(userId));
            var membership = new UserGroupMembershipEntity();
            membership.setId(new UserGroupMembershipEntity.Id(userId, groupId));
            membership.setUser(user);
            membership.setGroup(group);
            membership.setSource(UserGroupMembershipSource.IDP);
            membership.setJoinedAt(Instant.now());
            membershipRepository.save(membership);
        }
        return new HashSet<>(desired);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findGroupIdsForUser(UUID userId) {
        return membershipRepository.findGroupIdsForUser(userId);
    }

    private UserGroupEntity loadInOrganization(UUID groupId, UUID organizationId) {
        var entity = userGroupRepository.findById(groupId)
                .orElseThrow(() -> new UserGroupNotFoundException(groupId));
        if (!entity.getOrganization().getId().equals(organizationId)) {
            throw new UserGroupNotFoundException(groupId);
        }
        return entity;
    }

    private Map<UUID, Long> countMembersByGroupIds(List<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return groupIds.stream()
                .collect(Collectors.toMap(id -> id, membershipRepository::countByGroup_Id));
    }

    private UserGroupView toView(UserGroupEntity entity, long memberCount) {
        OrganizationEntity org = entity.getOrganization();
        return new UserGroupView(
                entity.getId(),
                org.getId(),
                entity.getName(),
                entity.getDescription(),
                memberCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static UserGroupMembershipView toMembershipView(UserGroupMembershipEntity entity) {
        UserEntity user = entity.getUser();
        return new UserGroupMembershipView(
                user.getId(),
                entity.getGroup().getId(),
                user.getEmail(),
                user.getDisplayName(),
                mapSource(entity.getSource()),
                entity.getJoinedAt());
    }

    private static UserGroupMembershipSourceType mapSource(UserGroupMembershipSource source) {
        return switch (source) {
            case MANUAL -> UserGroupMembershipSourceType.MANUAL;
            case IDP -> UserGroupMembershipSourceType.IDP;
        };
    }
}
