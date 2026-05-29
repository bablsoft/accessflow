package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserSlackMappingEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserSlackMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackLinkServiceTest {

    @Mock SlackLinkCodeStore linkCodeStore;
    @Mock UserSlackMappingRepository mappingRepository;
    @Mock UserQueryService userQueryService;
    @Mock SlackMessages messages;

    private SlackLinkService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SlackLinkService(linkCodeStore, mappingRepository, userQueryService, messages);
        lenient().when(messages.forOrg(any(), anyString())).thenAnswer(i -> i.getArgument(1));
        lenient().when(messages.forOrg(any(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
    }

    @Test
    void issueCodeDelegatesToStore() {
        var userId = UUID.randomUUID();
        var issued = new SlackLinkCodeStore.Issued("CODE", Instant.now());
        when(linkCodeStore.issue(userId)).thenReturn(issued);

        assertThat(service.issueCode(userId)).isSameAs(issued);
    }

    @Test
    void linkStatusReturnsSlackUserId() {
        var userId = UUID.randomUUID();
        var mapping = mapping(userId, "U123");
        when(mappingRepository.findByUserId(userId)).thenReturn(Optional.of(mapping));

        assertThat(service.linkStatus(userId)).contains("U123");
    }

    @Test
    void unlinkDeletesMapping() {
        var userId = UUID.randomUUID();
        service.unlink(userId);
        verify(mappingRepository).deleteByUserId(userId);
    }

    @Test
    void completeLinkRejectsBadUsageText() {
        assertThat(service.completeLink(orgId, "U1", "hello world there"))
                .isEqualTo("slack.link.usage");
        verify(linkCodeStore, never()).consume(anyString());
    }

    @Test
    void completeLinkRejectsInvalidCode() {
        when(linkCodeStore.consume("BAD")).thenReturn(Optional.empty());

        assertThat(service.completeLink(orgId, "U1", "link BAD"))
                .isEqualTo("slack.link.invalid_code");
    }

    @Test
    void completeLinkRejectsWhenUserOrgMismatch() {
        var userId = UUID.randomUUID();
        when(linkCodeStore.consume("CODE")).thenReturn(Optional.of(userId));
        when(userQueryService.findById(userId))
                .thenReturn(Optional.of(user(userId, UUID.randomUUID())));

        assertThat(service.completeLink(orgId, "U1", "link CODE"))
                .isEqualTo("slack.link.invalid_code");
        verify(mappingRepository, never()).save(any());
    }

    @Test
    void completeLinkRejectsWhenUserMissing() {
        var userId = UUID.randomUUID();
        when(linkCodeStore.consume("CODE")).thenReturn(Optional.of(userId));
        when(userQueryService.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.completeLink(orgId, "U1", "link CODE"))
                .isEqualTo("slack.link.invalid_code");
    }

    @Test
    void completeLinkPersistsNewMapping() {
        var userId = UUID.randomUUID();
        when(linkCodeStore.consume("CODE")).thenReturn(Optional.of(userId));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(userId, orgId)));
        when(mappingRepository.findByOrganizationIdAndSlackUserId(orgId, "U1")).thenReturn(Optional.empty());
        when(mappingRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(service.completeLink(orgId, "U1", "link CODE"))
                .isEqualTo("slack.link.success");

        var captor = ArgumentCaptor.forClass(UserSlackMappingEntity.class);
        verify(mappingRepository).save(captor.capture());
        assertThat(captor.getValue().getSlackUserId()).isEqualTo("U1");
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    void completeLinkUpdatesExistingUserMapping() {
        var userId = UUID.randomUUID();
        var existing = mapping(userId, "OLD");
        when(linkCodeStore.consume("CODE")).thenReturn(Optional.of(userId));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(userId, orgId)));
        when(mappingRepository.findByOrganizationIdAndSlackUserId(orgId, "U2")).thenReturn(Optional.empty());
        when(mappingRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        service.completeLink(orgId, "U2", "link CODE");

        assertThat(existing.getSlackUserId()).isEqualTo("U2");
        verify(mappingRepository).save(existing);
    }

    @Test
    void completeLinkRemovesStaleSlackMappingForDifferentUser() {
        var userId = UUID.randomUUID();
        var stale = mapping(UUID.randomUUID(), "U1");
        when(linkCodeStore.consume("CODE")).thenReturn(Optional.of(userId));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(userId, orgId)));
        when(mappingRepository.findByOrganizationIdAndSlackUserId(orgId, "U1")).thenReturn(Optional.of(stale));
        when(mappingRepository.findByUserId(userId)).thenReturn(Optional.empty());

        service.completeLink(orgId, "U1", "link CODE");

        verify(mappingRepository).delete(stale);
        verify(mappingRepository).flush();
    }

    private UserSlackMappingEntity mapping(UUID userId, String slackUserId) {
        var m = new UserSlackMappingEntity();
        m.setId(UUID.randomUUID());
        m.setOrganizationId(orgId);
        m.setUserId(userId);
        m.setSlackUserId(slackUserId);
        m.setCreatedAt(Instant.now());
        return m;
    }

    private static UserView user(UUID id, UUID organizationId) {
        return new UserView(id, "user@example.com", "User", UserRoleType.REVIEWER, organizationId,
                true, AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }
}
