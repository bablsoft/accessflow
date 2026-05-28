package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateDatasourceReviewerCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalDatasourceReviewerException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceReviewerEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceReviewerRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDatasourceReviewerServiceTest {

    @Mock DatasourceReviewerRepository reviewerRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository userGroupRepository;

    private DefaultDatasourceReviewerService service;
    private final UUID orgId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDatasourceReviewerService(reviewerRepository, datasourceRepository,
                userRepository, userGroupRepository);
    }

    @Test
    void listForDatasourceReturnsViewsSortedGroupsAfterUsers() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));

        var userReviewer = userReviewer(datasource, "alice@example.com");
        var groupReviewer = groupReviewer(datasource, "Reviewers");
        when(reviewerRepository.findAllByDatasource_Id(datasource.getId()))
                .thenReturn(List.of(groupReviewer, userReviewer));

        var result = service.listForDatasource(datasource.getId(), orgId);

        assertThat(result).hasSize(2);
        // users come first (sort key 0), then groups (sort key 1)
        assertThat(result.get(0).userId()).isNotNull();
        assertThat(result.get(0).userEmail()).isEqualTo("alice@example.com");
        assertThat(result.get(1).groupId()).isNotNull();
        assertThat(result.get(1).groupName()).isEqualTo("Reviewers");
    }

    @Test
    void listForDatasourceRejectsDatasourceFromOtherOrg() {
        var datasource = datasource(UUID.randomUUID());
        datasource.getOrganization().setId(UUID.randomUUID()); // different org
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));

        assertThatThrownBy(() -> service.listForDatasource(datasource.getId(), orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void listForDatasourceRejectsMissingDatasource() {
        var id = UUID.randomUUID();
        when(datasourceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForDatasource(id, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void addRejectsWhenNeitherUserNorGroupProvided() {
        var datasource = datasource(UUID.randomUUID());

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, null, null)))
                .isInstanceOf(IllegalDatasourceReviewerException.class);
        verify(reviewerRepository, never()).save(any());
    }

    @Test
    void addRejectsWhenBothUserAndGroupProvided() {
        var datasource = datasource(UUID.randomUUID());

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalDatasourceReviewerException.class);
    }

    @Test
    void addUserReviewerPersistsAndReturnsView() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        var creator = user(creatorId, orgId);
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        var user = user(UUID.randomUUID(), orgId);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(reviewerRepository.findByDatasource_IdAndUser_Id(datasource.getId(), user.getId()))
                .thenReturn(Optional.empty());
        when(reviewerRepository.save(any(DatasourceReviewerEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, user.getId(), null));

        assertThat(view.userId()).isEqualTo(user.getId());
        assertThat(view.groupId()).isNull();
        assertThat(view.userEmail()).isEqualTo(user.getEmail());
    }

    @Test
    void addUserReviewerRejectsCrossOrgUser() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        var creator = user(creatorId, orgId);
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        var foreignUser = user(UUID.randomUUID(), UUID.randomUUID());
        when(userRepository.findById(foreignUser.getId())).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, foreignUser.getId(), null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void addUserReviewerRejectsDuplicate() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        var creator = user(creatorId, orgId);
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        var user = user(UUID.randomUUID(), orgId);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(reviewerRepository.findByDatasource_IdAndUser_Id(datasource.getId(), user.getId()))
                .thenReturn(Optional.of(new DatasourceReviewerEntity()));

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, user.getId(), null)))
                .isInstanceOf(DatasourceReviewerAlreadyExistsException.class);
    }

    @Test
    void addGroupReviewerPersistsAndReturnsView() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(user(creatorId, orgId)));
        var group = group("Reviewers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(reviewerRepository.findByDatasource_IdAndGroup_Id(datasource.getId(), group.getId()))
                .thenReturn(Optional.empty());
        when(reviewerRepository.save(any(DatasourceReviewerEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, null, group.getId()));

        assertThat(view.userId()).isNull();
        assertThat(view.groupId()).isEqualTo(group.getId());
        assertThat(view.groupName()).isEqualTo("Reviewers");
    }

    @Test
    void addGroupReviewerRejectsCrossOrgGroup() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(user(creatorId, orgId)));
        var foreignGroup = group("Other");
        foreignGroup.getOrganization().setId(UUID.randomUUID());
        when(userGroupRepository.findById(foreignGroup.getId()))
                .thenReturn(Optional.of(foreignGroup));

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, null, foreignGroup.getId())))
                .isInstanceOf(UserGroupNotFoundException.class);
    }

    @Test
    void addGroupReviewerRejectsDuplicate() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(user(creatorId, orgId)));
        var group = group("Reviewers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(reviewerRepository.findByDatasource_IdAndGroup_Id(datasource.getId(), group.getId()))
                .thenReturn(Optional.of(new DatasourceReviewerEntity()));

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, null, group.getId())))
                .isInstanceOf(DatasourceReviewerAlreadyExistsException.class);
    }

    @Test
    void addRejectsWhenCreatorMissing() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        when(userRepository.findById(creatorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(new CreateDatasourceReviewerCommand(
                datasource.getId(), orgId, creatorId, UUID.randomUUID(), null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void removeDeletesMatchingReviewer() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        var reviewer = userReviewer(datasource, "alice@example.com");
        when(reviewerRepository.findById(reviewer.getId())).thenReturn(Optional.of(reviewer));

        service.remove(reviewer.getId(), datasource.getId(), orgId);

        verify(reviewerRepository).delete(reviewer);
    }

    @Test
    void removeRejectsReviewerFromDifferentDatasource() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        var otherDatasource = datasource(UUID.randomUUID());
        var reviewer = userReviewer(otherDatasource, "bob@example.com");
        when(reviewerRepository.findById(reviewer.getId())).thenReturn(Optional.of(reviewer));

        assertThatThrownBy(() ->
                service.remove(reviewer.getId(), datasource.getId(), orgId))
                .isInstanceOf(DatasourceReviewerNotFoundException.class);
        verify(reviewerRepository, never()).delete(any(DatasourceReviewerEntity.class));
    }

    @Test
    void removeRejectsUnknownReviewer() {
        var datasource = datasource(UUID.randomUUID());
        when(datasourceRepository.findById(datasource.getId())).thenReturn(Optional.of(datasource));
        var id = UUID.randomUUID();
        when(reviewerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(id, datasource.getId(), orgId))
                .isInstanceOf(DatasourceReviewerNotFoundException.class);
    }

    private DatasourceEntity datasource(UUID id) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var ds = new DatasourceEntity();
        ds.setId(id);
        ds.setOrganization(org);
        return ds;
    }

    private UserEntity user(UUID id, UUID userOrgId) {
        var org = new OrganizationEntity();
        org.setId(userOrgId);
        var u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@example.com");
        u.setDisplayName("User-" + id);
        u.setOrganization(org);
        return u;
    }

    private UserGroupEntity group(String name) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var g = new UserGroupEntity();
        g.setId(UUID.randomUUID());
        g.setOrganization(org);
        g.setName(name);
        return g;
    }

    private DatasourceReviewerEntity userReviewer(DatasourceEntity ds, String email) {
        var entity = new DatasourceReviewerEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(ds);
        var u = user(UUID.randomUUID(), orgId);
        u.setEmail(email);
        entity.setUser(u);
        entity.setCreatedBy(user(creatorId, orgId));
        return entity;
    }

    private DatasourceReviewerEntity groupReviewer(DatasourceEntity ds, String name) {
        var entity = new DatasourceReviewerEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(ds);
        entity.setGroup(group(name));
        entity.setCreatedBy(user(creatorId, orgId));
        return entity;
    }
}
