package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultHelpChatTokenLookupServiceTest {

    @Mock HelpChatMessageRepository messageRepository;
    @InjectMocks DefaultHelpChatTokenLookupService service;

    @Test
    void sumsTheOrganizationsHelpTokensSince() {
        var organizationId = UUID.randomUUID();
        var since = Instant.parse("2026-09-01T00:00:00Z");
        when(messageRepository.sumTokensSince(organizationId, since)).thenReturn(13_280L);

        assertThat(service.sumTokensSince(organizationId, since)).isEqualTo(13_280L);
    }
}
