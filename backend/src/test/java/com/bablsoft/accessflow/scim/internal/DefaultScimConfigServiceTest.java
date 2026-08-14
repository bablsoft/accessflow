package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.scim.api.ScimInvalidMappingException;
import com.bablsoft.accessflow.scim.api.UpdateScimConfigCommand;
import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimConfigEntity;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultScimConfigServiceTest {

    @Mock ScimConfigRepository configRepository;

    DefaultScimConfigService service;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultScimConfigService(configRepository);
    }

    @Test
    void getReturnsDefaultsWhenUnset() {
        when(configRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        var view = service.get(orgId);

        assertThat(view.enabled()).isFalse();
        assertThat(view.attrEmail()).isEqualTo("userName");
        assertThat(view.attrDisplayName()).isEqualTo("displayName");
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.ANALYST);
        assertThat(view.id()).isNull();
    }

    @Test
    void updateCreatesRowOnFirstSave() {
        when(configRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(configRepository.save(any(ScimConfigEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, new UpdateScimConfigCommand(
                true, "emails.primary", "name.formatted", UserRoleType.READONLY));

        assertThat(view.enabled()).isTrue();
        assertThat(view.attrEmail()).isEqualTo("emails.primary");
        assertThat(view.attrDisplayName()).isEqualTo("name.formatted");
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.READONLY);
        assertThat(view.organizationId()).isEqualTo(orgId);
    }

    @Test
    void updateLeavesOmittedFieldsUnchanged() {
        var entity = new ScimConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setEnabled(true);
        entity.setAttrEmail("emails.primary");
        when(configRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(configRepository.save(any(ScimConfigEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, new UpdateScimConfigCommand(null, null, null, null));

        assertThat(view.enabled()).isTrue();
        assertThat(view.attrEmail()).isEqualTo("emails.primary");
    }

    @Test
    void updateRejectsUnknownEmailSource() {
        assertThatThrownBy(() -> service.update(orgId,
                new UpdateScimConfigCommand(null, "nickName", null, null)))
                .isInstanceOf(ScimInvalidMappingException.class);
        verify(configRepository, never()).save(any());
    }

    @Test
    void updateRejectsUnknownDisplayNameSource() {
        assertThatThrownBy(() -> service.update(orgId,
                new UpdateScimConfigCommand(null, null, "title", null)))
                .isInstanceOf(ScimInvalidMappingException.class);
    }

    @Test
    void isEnabledDelegatesToRepository() {
        when(configRepository.existsByOrganizationIdAndEnabledTrue(orgId)).thenReturn(true);

        assertThat(service.isEnabled(orgId)).isTrue();
    }
}
