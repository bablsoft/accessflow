package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.ApplicationNameSource;
import com.bablsoft.accessflow.core.api.ClientApplication;
import com.bablsoft.accessflow.security.api.RequestApplicationService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationAuditMetadataContributorTest {

    private final RequestApplicationService requestApplicationService = mock(RequestApplicationService.class);
    private final ApplicationAuditMetadataContributor contributor =
            new ApplicationAuditMetadataContributor(requestApplicationService);

    @Test
    void emptyWhenNoApplicationIsKnown() {
        when(requestApplicationService.current()).thenReturn(Optional.empty());

        assertThat(contributor.contribute()).isEmpty();
    }

    @Test
    void stampsTheNameAndALowercaseSource() {
        when(requestApplicationService.current()).thenReturn(
                Optional.of(new ClientApplication("reporting", ApplicationNameSource.API_KEY)));

        assertThat(contributor.contribute()).isEqualTo(Map.of(
                "application_name", "reporting",
                "application_name_source", "api_key"));
    }

    @Test
    void marksAHeaderSource() {
        when(requestApplicationService.current()).thenReturn(
                Optional.of(new ClientApplication("cli", ApplicationNameSource.HEADER)));

        assertThat(contributor.contribute()).containsEntry("application_name_source", "header");
    }
}
