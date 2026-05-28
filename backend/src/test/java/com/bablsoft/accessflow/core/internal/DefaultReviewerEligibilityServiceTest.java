package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceReviewerEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceReviewerRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewerEligibilityServiceTest {

    @Mock DatasourceReviewerRepository reviewerRepository;
    @Mock UserGroupMembershipRepository membershipRepository;

    private DefaultReviewerEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new DefaultReviewerEligibilityService(reviewerRepository, membershipRepository);
    }

    @Test
    void emptyResultReturnsEmptyOptional() {
        var datasourceId = UUID.randomUUID();
        when(reviewerRepository.findAllByDatasource_Id(datasourceId)).thenReturn(List.of());

        var result = service.findEligibleReviewerIds(datasourceId);

        assertThat(result).isEmpty();
    }

    @Test
    void directUserReviewersAreReturned() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = new DatasourceReviewerEntity();
        var user = new UserEntity();
        user.setId(userId);
        entity.setUser(user);
        when(reviewerRepository.findAllByDatasource_Id(datasourceId)).thenReturn(List.of(entity));

        var result = service.findEligibleReviewerIds(datasourceId);

        assertThat(result).hasValueSatisfying(set -> assertThat(set).containsExactly(userId));
    }

    @Test
    void groupReviewerExpandsToMembers() {
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var entity = new DatasourceReviewerEntity();
        var group = new UserGroupEntity();
        group.setId(groupId);
        entity.setGroup(group);
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();
        when(reviewerRepository.findAllByDatasource_Id(datasourceId)).thenReturn(List.of(entity));
        when(membershipRepository.findUserIdsInGroups(anyList()))
                .thenReturn(List.of(member1, member2));

        var result = service.findEligibleReviewerIds(datasourceId);

        assertThat(result).hasValueSatisfying(set ->
                assertThat(set).containsExactlyInAnyOrder(member1, member2));
    }

    @Test
    void hasDatasourceScopedReviewersDelegatesToRepository() {
        var datasourceId = UUID.randomUUID();
        when(reviewerRepository.existsByDatasource_Id(datasourceId)).thenReturn(true);

        assertThat(service.hasDatasourceScopedReviewers(datasourceId)).isTrue();
    }
}
