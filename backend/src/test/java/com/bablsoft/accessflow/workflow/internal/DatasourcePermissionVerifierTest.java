package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourcePermissionVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock MessageSource messageSource;

    private DatasourcePermissionVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new DatasourcePermissionVerifier(permissionLookupService, messageSource,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DatasourceUserPermissionView permission(boolean canRead, boolean canWrite,
                                                    List<String> allowedTables,
                                                    Instant expiresAt) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                canRead, canWrite, false, false, null, allowedTables, null, expiresAt);
    }

    @Test
    void verifyPassesWhenPermissionGrantsCapabilityAndNoAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, false, null, null)));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of("public.users")))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsWhenNoPermissionExists() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyThrowsWhenPermissionIsExpired() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, null, NOW.minusSeconds(1))));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyPassesWhenPermissionExpiresInTheFuture() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, null, NOW.plus(Duration.ofHours(1)))));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsWhenSelectLacksReadCapability() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(false, true, null, null)));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyPassesWhenInsertHasWriteCapability() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(false, true, null, null)));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.INSERT,
                Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsLocalizedMessageWhenReferencedTableOutsideAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, List.of("public.users"), null)));
        when(messageSource.getMessage(eq("error.permission.table_not_allowed"), any(),
                any(Locale.class)))
                .thenReturn("TABLE_NOT_ALLOWED_MARKER");

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of("public.orders")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("TABLE_NOT_ALLOWED_MARKER");
    }

    @Test
    void verifyPassesWhenNoTablesReferencedDespiteAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, List.of("public.users"), null)));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                Set.of()))
                .doesNotThrowAnyException();
    }
}
