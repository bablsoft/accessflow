package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryTemplateRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryTemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
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
class DefaultQueryTemplateVersioningServiceTest {

    @Mock QueryTemplateVersionRepository versionRepository;
    @Mock QueryTemplateRepository templateRepository;
    @Mock UserQueryService userQueryService;

    private DefaultQueryTemplateVersioningService service;
    private final UUID orgId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultQueryTemplateVersioningService(
                versionRepository, templateRepository, userQueryService);
    }

    @Test
    void listVersionsReturnsPageWithAuthorNames() {
        when(templateRepository.findById(templateId))
                .thenReturn(Optional.of(template(QueryTemplateVisibility.TEAM)));
        var version = version(2, QueryTemplateChangeType.UPDATED);
        Page<QueryTemplateVersionEntity> page = new PageImpl<>(List.of(version));
        when(versionRepository.findByTemplateIdOrderByVersionNumberDesc(any(UUID.class), any(Pageable.class)))
                .thenReturn(page);
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView("Alice")));

        var result = service.listVersions(templateId, orgId, otherUser, PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).versionNumber()).isEqualTo(2);
        assertThat(result.content().get(0).authorDisplayName()).isEqualTo("Alice");
    }

    @Test
    void listVersionsEmptySkipsAuthorLookup() {
        when(templateRepository.findById(templateId))
                .thenReturn(Optional.of(template(QueryTemplateVisibility.TEAM)));
        when(versionRepository.findByTemplateIdOrderByVersionNumberDesc(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));

        var result = service.listVersions(templateId, orgId, otherUser, PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        verify(userQueryService, never()).findById(any());
    }

    @Test
    void listVersionsHidesPrivateTemplateFromNonOwner() {
        when(templateRepository.findById(templateId))
                .thenReturn(Optional.of(template(QueryTemplateVisibility.PRIVATE)));

        assertThatThrownBy(() -> service.listVersions(templateId, orgId, otherUser, PageRequest.of(0, 20)))
                .isInstanceOf(QueryTemplateNotFoundException.class);
    }

    @Test
    void listVersionsRejectsCrossOrganization() {
        var template = template(QueryTemplateVisibility.TEAM);
        template.setOrganizationId(UUID.randomUUID());
        when(templateRepository.findById(templateId)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.listVersions(templateId, orgId, otherUser, PageRequest.of(0, 20)))
                .isInstanceOf(QueryTemplateNotFoundException.class);
    }

    @Test
    void getVersionReturnsViewForVisibleTemplate() {
        when(templateRepository.findById(templateId))
                .thenReturn(Optional.of(template(QueryTemplateVisibility.TEAM)));
        var version = version(1, QueryTemplateChangeType.CREATED);
        when(versionRepository.findByTemplateIdAndId(templateId, version.getId()))
                .thenReturn(Optional.of(version));
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView("Alice")));

        var view = service.getVersion(templateId, version.getId(), orgId, otherUser);

        assertThat(view.versionNumber()).isEqualTo(1);
        assertThat(view.authorDisplayName()).isEqualTo("Alice");
    }

    @Test
    void getVersionThrowsWhenVersionAbsent() {
        when(templateRepository.findById(templateId))
                .thenReturn(Optional.of(template(QueryTemplateVisibility.TEAM)));
        UUID versionId = UUID.randomUUID();
        when(versionRepository.findByTemplateIdAndId(templateId, versionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVersion(templateId, versionId, orgId, otherUser))
                .isInstanceOf(QueryTemplateVersionNotFoundException.class);
    }

    @Test
    void requireVersionThrowsWhenAbsent() {
        UUID versionId = UUID.randomUUID();
        when(versionRepository.findByTemplateIdAndId(templateId, versionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireVersion(templateId, versionId))
                .isInstanceOf(QueryTemplateVersionNotFoundException.class);
    }

    @Test
    void recordSnapshotCreatedNumbersFirstVersionOne() {
        when(versionRepository.findTopByTemplateIdOrderByVersionNumberDesc(templateId))
                .thenReturn(Optional.empty());

        service.recordSnapshot(template(QueryTemplateVisibility.TEAM), owner, QueryTemplateChangeType.CREATED);

        ArgumentCaptor<QueryTemplateVersionEntity> saved =
                ArgumentCaptor.forClass(QueryTemplateVersionEntity.class);
        verify(versionRepository).save(saved.capture());
        assertThat(saved.getValue().getVersionNumber()).isEqualTo(1);
        assertThat(saved.getValue().getChangeType()).isEqualTo(QueryTemplateChangeType.CREATED);
        assertThat(saved.getValue().getAuthorId()).isEqualTo(owner);
        assertThat(saved.getValue().getTemplateId()).isEqualTo(templateId);
    }

    @Test
    void recordSnapshotUpdatedInsertsNextNumberWhenContentChanged() {
        var latest = version(1, QueryTemplateChangeType.CREATED);
        latest.setBody("SELECT 1");
        when(versionRepository.findTopByTemplateIdOrderByVersionNumberDesc(templateId))
                .thenReturn(Optional.of(latest));
        var template = template(QueryTemplateVisibility.TEAM);
        template.setBody("SELECT 2");

        service.recordSnapshot(template, owner, QueryTemplateChangeType.UPDATED);

        ArgumentCaptor<QueryTemplateVersionEntity> saved =
                ArgumentCaptor.forClass(QueryTemplateVersionEntity.class);
        verify(versionRepository).save(saved.capture());
        assertThat(saved.getValue().getVersionNumber()).isEqualTo(2);
        assertThat(saved.getValue().getBody()).isEqualTo("SELECT 2");
    }

    @Test
    void recordSnapshotUpdatedNoOpsWhenContentUnchanged() {
        var template = template(QueryTemplateVisibility.TEAM);
        template.setBody("SELECT 1");
        template.setTags(new String[]{"a"});
        var latest = version(1, QueryTemplateChangeType.CREATED);
        latest.setBody("SELECT 1");
        latest.setName(template.getName());
        latest.setDescription(template.getDescription());
        latest.setVisibility(template.getVisibility());
        latest.setDatasourceId(template.getDatasourceId());
        latest.setTags(new String[]{"a"});
        when(versionRepository.findTopByTemplateIdOrderByVersionNumberDesc(templateId))
                .thenReturn(Optional.of(latest));

        service.recordSnapshot(template, owner, QueryTemplateChangeType.UPDATED);

        verify(versionRepository, never()).save(any());
    }

    @Test
    void recordSnapshotRestoredAlwaysInsertsEvenWhenUnchanged() {
        var template = template(QueryTemplateVisibility.TEAM);
        template.setBody("SELECT 1");
        template.setTags(new String[]{"a"});
        var latest = version(1, QueryTemplateChangeType.CREATED);
        latest.setBody("SELECT 1");
        latest.setName(template.getName());
        latest.setDescription(template.getDescription());
        latest.setVisibility(template.getVisibility());
        latest.setDatasourceId(template.getDatasourceId());
        latest.setTags(new String[]{"a"});
        when(versionRepository.findTopByTemplateIdOrderByVersionNumberDesc(templateId))
                .thenReturn(Optional.of(latest));

        service.recordSnapshot(template, owner, QueryTemplateChangeType.RESTORED);

        ArgumentCaptor<QueryTemplateVersionEntity> saved =
                ArgumentCaptor.forClass(QueryTemplateVersionEntity.class);
        verify(versionRepository).save(saved.capture());
        assertThat(saved.getValue().getVersionNumber()).isEqualTo(2);
        assertThat(saved.getValue().getChangeType()).isEqualTo(QueryTemplateChangeType.RESTORED);
    }

    private QueryTemplateEntity template(QueryTemplateVisibility visibility) {
        var entity = new QueryTemplateEntity();
        entity.setId(templateId);
        entity.setOrganizationId(orgId);
        entity.setOwnerId(owner);
        entity.setName("Top");
        entity.setBody("SELECT 1");
        entity.setVisibility(visibility);
        entity.setTags(new String[]{"a"});
        entity.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return entity;
    }

    private QueryTemplateVersionEntity version(int number, QueryTemplateChangeType changeType) {
        var entity = new QueryTemplateVersionEntity();
        entity.setId(UUID.randomUUID());
        entity.setTemplateId(templateId);
        entity.setOrganizationId(orgId);
        entity.setVersionNumber(number);
        entity.setName("Top");
        entity.setBody("SELECT 1");
        entity.setTags(new String[]{"a"});
        entity.setVisibility(QueryTemplateVisibility.TEAM);
        entity.setChangeType(changeType);
        entity.setAuthorId(owner);
        entity.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return entity;
    }

    private UserView userView(String displayName) {
        return new UserView(owner, displayName + "@x.com", displayName, UserRoleType.ANALYST,
                orgId, true, AuthProviderType.LOCAL, null,
                null, null, false, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
