package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateAccessDeniedException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateFilter;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNameAlreadyExistsException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateService.CreateQueryTemplateCommand;
import com.bablsoft.accessflow.workflow.api.QueryTemplateService.UpdateQueryTemplateCommand;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryTemplateServiceTest {

    @Mock QueryTemplateRepository queryTemplateRepository;
    @Mock UserQueryService userQueryService;
    @Mock QueryTemplateVersionRecorder versionRecorder;

    private DefaultQueryTemplateService service;
    private final UUID orgId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultQueryTemplateService(queryTemplateRepository, userQueryService, versionRecorder);
    }

    @Test
    void listReturnsPageWithOwnerNames() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.TEAM, "Top users");
        Page<QueryTemplateEntity> page = new PageImpl<>(List.of(entity));
        when(queryTemplateRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView(owner, "Alice")));

        var result = service.list(orgId, owner,
                new QueryTemplateFilter(null, null, null, null),
                new PageRequest(0, 20, null));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).name()).isEqualTo("Top users");
        assertThat(result.content().get(0).ownerDisplayName()).isEqualTo("Alice");
    }

    @Test
    void listRejectsNullOrganization() {
        assertThatThrownBy(() -> service.list(null, owner, null, new PageRequest(0, 20, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listRejectsNullCaller() {
        assertThatThrownBy(() -> service.list(orgId, null, null, new PageRequest(0, 20, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getReturnsTeamTemplateForNonOwner() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.TEAM, "Shared");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView(owner, "Alice")));

        var view = service.get(entity.getId(), orgId, otherUser);

        assertThat(view.name()).isEqualTo("Shared");
        assertThat(view.ownerDisplayName()).isEqualTo("Alice");
    }

    @Test
    void getHidesPrivateTemplateFromNonOwner() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Secret");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.get(entity.getId(), orgId, otherUser))
                .isInstanceOf(QueryTemplateNotFoundException.class);
    }

    @Test
    void getRejectsCrossOrganization() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.TEAM, "Mine");
        entity.setOrganizationId(UUID.randomUUID());
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.get(entity.getId(), orgId, otherUser))
                .isInstanceOf(QueryTemplateNotFoundException.class);
    }

    @Test
    void getThrowsWhenAbsent() {
        UUID missing = UUID.randomUUID();
        when(queryTemplateRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(missing, orgId, owner))
                .isInstanceOf(QueryTemplateNotFoundException.class);
    }

    @Test
    void createRejectsDuplicateName() {
        when(queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(orgId, owner, "Top"))
                .thenReturn(Optional.of(templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Top")));

        var command = new CreateQueryTemplateCommand(orgId, owner, null, "Top",
                "SELECT 1", null, List.of("a"), QueryTemplateVisibility.PRIVATE);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(QueryTemplateNameAlreadyExistsException.class);
        verify(queryTemplateRepository, never()).save(any());
    }

    @Test
    void createRejectsNullOrganization() {
        var command = new CreateQueryTemplateCommand(null, owner, null, "X", "SELECT 1",
                null, null, QueryTemplateVisibility.PRIVATE);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createPersistsTemplateAndReturnsView() {
        when(queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(eq(orgId), eq(owner), any()))
                .thenReturn(Optional.empty());
        when(queryTemplateRepository.save(any(QueryTemplateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView(owner, "Alice")));

        var command = new CreateQueryTemplateCommand(orgId, owner, null, "  New  ", "SELECT 1",
                "desc", List.of("billing", "  ", "billing"), QueryTemplateVisibility.TEAM);
        var view = service.create(command);

        ArgumentCaptor<QueryTemplateEntity> saved = ArgumentCaptor.forClass(QueryTemplateEntity.class);
        verify(queryTemplateRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("New");
        // Tag list normalised: trimmed, blanks dropped, deduplicated.
        assertThat(saved.getValue().getTags()).containsExactly("billing");
        assertThat(view.visibility()).isEqualTo(QueryTemplateVisibility.TEAM);
        assertThat(view.ownerDisplayName()).isEqualTo("Alice");
        verify(versionRecorder).recordSnapshot(saved.getValue(), owner, QueryTemplateChangeType.CREATED);
    }

    @Test
    void updateOwnerMutatesAllFields() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Old");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(orgId, owner, "NewName"))
                .thenReturn(Optional.empty());
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView(owner, "Alice")));

        var view = service.update(entity.getId(), orgId, owner, new UpdateQueryTemplateCommand(
                null, "NewName", "SELECT 2", "desc2",
                List.of("x"), QueryTemplateVisibility.TEAM));

        assertThat(view.name()).isEqualTo("NewName");
        assertThat(view.body()).isEqualTo("SELECT 2");
        assertThat(view.description()).isEqualTo("desc2");
        assertThat(view.tags()).containsExactly("x");
        assertThat(view.visibility()).isEqualTo(QueryTemplateVisibility.TEAM);
        verify(versionRecorder).recordSnapshot(entity, owner, QueryTemplateChangeType.UPDATED);
    }

    @Test
    void updateRejectsDuplicateNameFromAnotherTemplate() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Old");
        var other = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Taken");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(orgId, owner, "Taken"))
                .thenReturn(Optional.of(other));

        var cmd = new UpdateQueryTemplateCommand(null, "Taken", null, null, null, null);
        assertThatThrownBy(() -> service.update(entity.getId(), orgId, owner, cmd))
                .isInstanceOf(QueryTemplateNameAlreadyExistsException.class);
    }

    @Test
    void updateForbidsNonOwnerOnTeamTemplate() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.TEAM, "Shared");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        var cmd = new UpdateQueryTemplateCommand(null, "Renamed", null, null, null, null);
        assertThatThrownBy(() -> service.update(entity.getId(), orgId, otherUser, cmd))
                .isInstanceOf(QueryTemplateAccessDeniedException.class);
    }

    @Test
    void deleteOwnerRemovesRow() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Old");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        service.delete(entity.getId(), orgId, owner);

        verify(queryTemplateRepository).delete(entity);
    }

    @Test
    void deleteForbidsNonOwnerOnTeamTemplate() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.TEAM, "Shared");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.delete(entity.getId(), orgId, otherUser))
                .isInstanceOf(QueryTemplateAccessDeniedException.class);
        verify(queryTemplateRepository, never()).delete(any(QueryTemplateEntity.class));
    }

    @Test
    void deletePrivateInvisibleToNonOwnerReports404() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Mine");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.delete(entity.getId(), orgId, otherUser))
                .isInstanceOf(QueryTemplateNotFoundException.class);
    }

    @Test
    void restoreOwnerAppliesSnapshotAndRecordsRestoredVersion() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Current");
        entity.setBody("SELECT 999");
        var version = versionOf(entity.getId(), "Original", "SELECT 1", QueryTemplateVisibility.TEAM);
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(versionRecorder.requireVersion(entity.getId(), version.getId())).thenReturn(version);
        when(queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(orgId, owner, "Original"))
                .thenReturn(Optional.empty());
        when(userQueryService.findById(owner)).thenReturn(Optional.of(userView(owner, "Alice")));

        var view = service.restoreVersion(entity.getId(), version.getId(), orgId, owner);

        assertThat(view.name()).isEqualTo("Original");
        assertThat(view.body()).isEqualTo("SELECT 1");
        assertThat(view.visibility()).isEqualTo(QueryTemplateVisibility.TEAM);
        assertThat(entity.getBody()).isEqualTo("SELECT 1");
        verify(versionRecorder).recordSnapshot(entity, owner, QueryTemplateChangeType.RESTORED);
    }

    @Test
    void restoreForbidsNonOwnerOnTeamTemplate() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.TEAM, "Shared");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.restoreVersion(entity.getId(), UUID.randomUUID(), orgId, otherUser))
                .isInstanceOf(QueryTemplateAccessDeniedException.class);
        verify(versionRecorder, never()).recordSnapshot(any(), any(), any());
    }

    @Test
    void restoreThrowsWhenVersionMissing() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Current");
        UUID versionId = UUID.randomUUID();
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(versionRecorder.requireVersion(entity.getId(), versionId))
                .thenThrow(new QueryTemplateVersionNotFoundException(entity.getId(), versionId));

        assertThatThrownBy(() -> service.restoreVersion(entity.getId(), versionId, orgId, owner))
                .isInstanceOf(QueryTemplateVersionNotFoundException.class);
        verify(versionRecorder, never()).recordSnapshot(any(), any(), any());
    }

    @Test
    void restoreRejectsWhenRestoredNameNowCollides() {
        var entity = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Current");
        var version = versionOf(entity.getId(), "Taken", "SELECT 1", QueryTemplateVisibility.PRIVATE);
        var other = templateOwnedBy(owner, QueryTemplateVisibility.PRIVATE, "Taken");
        when(queryTemplateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(versionRecorder.requireVersion(entity.getId(), version.getId())).thenReturn(version);
        when(queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(orgId, owner, "Taken"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.restoreVersion(entity.getId(), version.getId(), orgId, owner))
                .isInstanceOf(QueryTemplateNameAlreadyExistsException.class);
        verify(versionRecorder, never()).recordSnapshot(any(), any(), any());
    }

    private QueryTemplateVersionEntity versionOf(UUID templateId, String name, String body,
                                                 QueryTemplateVisibility visibility) {
        var version = new QueryTemplateVersionEntity();
        version.setId(UUID.randomUUID());
        version.setTemplateId(templateId);
        version.setOrganizationId(orgId);
        version.setVersionNumber(1);
        version.setName(name);
        version.setBody(body);
        version.setTags(new String[]{"a"});
        version.setVisibility(visibility);
        version.setChangeType(QueryTemplateChangeType.CREATED);
        version.setAuthorId(owner);
        version.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return version;
    }

    private QueryTemplateEntity templateOwnedBy(UUID ownerId, QueryTemplateVisibility visibility, String name) {
        var entity = new QueryTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setOwnerId(ownerId);
        entity.setName(name);
        entity.setBody("SELECT 1");
        entity.setVisibility(visibility);
        entity.setTags(new String[]{"a"});
        entity.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return entity;
    }

    private UserView userView(UUID id, String displayName) {
        return new UserView(id, displayName + "@x.com", displayName, UserRoleType.ANALYST,
                orgId, true, AuthProviderType.LOCAL, null,
                null, null, false, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
