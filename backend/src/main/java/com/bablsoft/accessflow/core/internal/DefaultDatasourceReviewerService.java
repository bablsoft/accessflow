package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateDatasourceReviewerCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerService;
import com.bablsoft.accessflow.core.api.DatasourceReviewerView;
import com.bablsoft.accessflow.core.api.IllegalDatasourceReviewerException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceReviewerEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceReviewerRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDatasourceReviewerService implements DatasourceReviewerService {

    private final DatasourceReviewerRepository reviewerRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceReviewerView> listForDatasource(UUID datasourceId, UUID organizationId) {
        loadDatasourceInOrganization(datasourceId, organizationId);
        return reviewerRepository.findAllByDatasource_Id(datasourceId).stream()
                .map(DefaultDatasourceReviewerService::toView)
                .sorted(Comparator
                        .comparing((DatasourceReviewerView v) -> v.groupName() == null ? 0 : 1)
                        .thenComparing(v -> sortKey(v),
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Override
    @Transactional
    public DatasourceReviewerView add(CreateDatasourceReviewerCommand command) {
        if ((command.userId() == null) == (command.groupId() == null)) {
            throw new IllegalDatasourceReviewerException(
                    "Exactly one of userId or groupId must be provided");
        }
        DatasourceEntity datasource = loadDatasourceInOrganization(command.datasourceId(),
                command.organizationId());
        UserEntity creator = userRepository.findById(command.createdBy())
                .orElseThrow(() -> new UserNotFoundException(command.createdBy()));
        DatasourceReviewerEntity entity = new DatasourceReviewerEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(datasource);
        entity.setCreatedBy(creator);
        entity.setCreatedAt(Instant.now());
        if (command.userId() != null) {
            var user = userRepository.findById(command.userId())
                    .orElseThrow(() -> new UserNotFoundException(command.userId()));
            if (!user.getOrganization().getId().equals(command.organizationId())) {
                throw new UserNotFoundException(command.userId());
            }
            reviewerRepository.findByDatasource_IdAndUser_Id(command.datasourceId(), user.getId())
                    .ifPresent(existing -> {
                        throw new DatasourceReviewerAlreadyExistsException(
                                "User is already a reviewer for this datasource");
                    });
            entity.setUser(user);
        } else {
            UserGroupEntity group = userGroupRepository.findById(command.groupId())
                    .orElseThrow(() -> new UserGroupNotFoundException(command.groupId()));
            if (!group.getOrganization().getId().equals(command.organizationId())) {
                throw new UserGroupNotFoundException(command.groupId());
            }
            reviewerRepository.findByDatasource_IdAndGroup_Id(command.datasourceId(), group.getId())
                    .ifPresent(existing -> {
                        throw new DatasourceReviewerAlreadyExistsException(
                                "Group is already a reviewer for this datasource");
                    });
            entity.setGroup(group);
        }
        return toView(reviewerRepository.save(entity));
    }

    @Override
    @Transactional
    public void remove(UUID reviewerId, UUID datasourceId, UUID organizationId) {
        loadDatasourceInOrganization(datasourceId, organizationId);
        DatasourceReviewerEntity entity = reviewerRepository.findById(reviewerId)
                .orElseThrow(() -> new DatasourceReviewerNotFoundException(reviewerId));
        if (!entity.getDatasource().getId().equals(datasourceId)) {
            throw new DatasourceReviewerNotFoundException(reviewerId);
        }
        reviewerRepository.delete(entity);
    }

    private DatasourceEntity loadDatasourceInOrganization(UUID datasourceId, UUID organizationId) {
        DatasourceEntity datasource = datasourceRepository.findById(datasourceId)
                .orElseThrow(() -> new DatasourceNotFoundException(datasourceId));
        if (!datasource.getOrganization().getId().equals(organizationId)) {
            throw new DatasourceNotFoundException(datasourceId);
        }
        return datasource;
    }

    private static String sortKey(DatasourceReviewerView v) {
        if (v.groupName() != null) {
            return v.groupName();
        }
        if (v.userEmail() != null) {
            return v.userEmail();
        }
        return "";
    }

    private static DatasourceReviewerView toView(DatasourceReviewerEntity entity) {
        UserEntity user = entity.getUser();
        UserGroupEntity group = entity.getGroup();
        return new DatasourceReviewerView(
                entity.getId(),
                entity.getDatasource().getId(),
                user == null ? null : user.getId(),
                user == null ? null : user.getEmail(),
                user == null ? null : user.getDisplayName(),
                group == null ? null : group.getId(),
                group == null ? null : group.getName(),
                entity.getCreatedBy().getId(),
                entity.getCreatedAt()
        );
    }
}
