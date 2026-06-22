package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.ai.api.TextToSqlDisabledException;
import com.bablsoft.accessflow.ai.api.TextToSqlNotConfiguredException;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.LocalizationConfigView;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class DefaultTextToSqlServiceTest {

    @Mock AiAnalyzerStrategy strategy;
    @Mock AiConfigRepository aiConfigRepository;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock LocalizationConfigService localizationConfigService;
    @Mock QueryParser queryParser;
    @Mock AiRateLimiter aiRateLimiter;

    private final SystemPromptRenderer promptRenderer = new SystemPromptRenderer();

    private DefaultTextToSqlService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID aiConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultTextToSqlService(strategy, aiConfigRepository, promptRenderer,
                datasourceLookupService, datasourceAdminService, permissionLookupService,
                localizationConfigService, queryParser, aiRateLimiter);
        org.mockito.Mockito.lenient().when(localizationConfigService.getOrDefault(any()))
                .thenReturn(new LocalizationConfigView(organizationId, List.of("en"), "en", "en"));
        org.mockito.Mockito.lenient().when(aiConfigRepository.findById(aiConfigId))
                .thenReturn(Optional.of(aiConfigEntity()));
    }

    private AiConfigEntity aiConfigEntity() {
        var entity = new AiConfigEntity();
        entity.setId(aiConfigId);
        entity.setOrganizationId(organizationId);
        entity.setName("Primary");
        entity.setProvider(AiProviderType.ANTHROPIC);
        entity.setModel("model-x");
        return entity;
    }

    private DatasourceConnectionDescriptor descriptor(boolean textToSqlEnabled, UUID boundAiConfigId) {
        return descriptor(textToSqlEnabled, boundAiConfigId, DbType.POSTGRESQL);
    }

    private DatasourceConnectionDescriptor descriptor(boolean textToSqlEnabled, UUID boundAiConfigId,
                                                      DbType dbType) {
        return new DatasourceConnectionDescriptor(datasourceId, organizationId, dbType, "h",
                5432, "db", "u", "ENC(p)", SslMode.DISABLE, 5, 1000, true, boundAiConfigId,
                textToSqlEnabled, null, null, null, null, null, null, true);
    }

    private DatabaseSchemaView schemaView() {
        return new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("orders", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true),
                                new DatabaseSchemaView.Column("created_at", "timestamptz", false, false)),
                                List.of())))));
    }

    private GeneratedSqlResult sampleResult() {
        return new GeneratedSqlResult("SELECT id FROM orders", AiProviderType.ANTHROPIC, "model-x", 30, 12);
    }

    @Test
    void generateSqlBuildsSchemaContextAndForwardsToStrategy() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(true, aiConfigId)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        ArgumentCaptor<String> ctx = ArgumentCaptor.forClass(String.class);
        when(strategy.generateSql(eq("orders for last 5 days"), eq(DbType.POSTGRESQL), ctx.capture(),
                eq("en"), eq(aiConfigId))).thenReturn(sampleResult());

        var result = service.generateSql(datasourceId, "orders for last 5 days", userId, organizationId, false);

        assertThat(result.sql()).isEqualTo("SELECT id FROM orders");
        assertThat(ctx.getValue()).contains("public.orders(");
    }

    @Test
    void generateSqlPassesRestrictedColumnsIntoSchemaContext() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(true, aiConfigId)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        var permission = new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                true, false, false, false, List.of(), List.of(), List.of("public.orders.created_at"), null);
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.of(permission));
        ArgumentCaptor<String> ctx = ArgumentCaptor.forClass(String.class);
        when(strategy.generateSql(any(), eq(DbType.POSTGRESQL), ctx.capture(), eq("en"), eq(aiConfigId)))
                .thenReturn(sampleResult());

        service.generateSql(datasourceId, "recent orders", userId, organizationId, false);

        assertThat(ctx.getValue()).contains("created_at timestamptz not null *RESTRICTED*");
    }

    @Test
    void generateSqlForNoSqlEngineAttachesSyntaxAndValidatesDraft() {
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(true, aiConfigId, DbType.MONGODB)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        var mongoDraft = new GeneratedSqlResult("db.orders.find({})", AiProviderType.ANTHROPIC, "model-x", 30, 12);
        when(strategy.generateSql(any(), eq(DbType.MONGODB), any(), eq("en"), eq(aiConfigId)))
                .thenReturn(mongoDraft);

        var result = service.generateSql(datasourceId, "recent orders", userId, organizationId, false);

        assertThat(result.sql()).isEqualTo("db.orders.find({})");
        assertThat(result.syntax()).isEqualTo("shell");
        verify(queryParser).parse("db.orders.find({})", DbType.MONGODB);
    }

    @Test
    void generateSqlRejectsUnparseableDraftAsParseException() {
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(true, aiConfigId, DbType.MONGODB)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        when(strategy.generateSql(any(), eq(DbType.MONGODB), any(), eq("en"), eq(aiConfigId)))
                .thenReturn(new GeneratedSqlResult("db.orders.wat(", AiProviderType.ANTHROPIC, "model-x", 30, 12));
        org.mockito.Mockito.doThrow(new InvalidSqlException("unparseable"))
                .when(queryParser).parse(any(), eq(DbType.MONGODB));

        assertThatThrownBy(() -> service.generateSql(datasourceId, "x", userId, organizationId, false))
                .isInstanceOf(AiAnalysisParseException.class)
                .hasMessageContaining("did not parse");
    }

    @Test
    void generateSqlThrowsWhenDatasourceMissing() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateSql(datasourceId, "x", userId, organizationId, false))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("Datasource not found");
        verify(strategy, never()).generateSql(any(), any(), any(), any(), any());
    }

    @Test
    void generateSqlThrowsWhenTextToSqlDisabled() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(false, aiConfigId)));

        assertThatThrownBy(() -> service.generateSql(datasourceId, "x", userId, organizationId, false))
                .isInstanceOf(TextToSqlDisabledException.class);
        verify(strategy, never()).generateSql(any(), any(), any(), any(), any());
    }

    @Test
    void generateSqlThrowsWhenAiConfigNotBound() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(true, null)));

        assertThatThrownBy(() -> service.generateSql(datasourceId, "x", userId, organizationId, false))
                .isInstanceOf(TextToSqlNotConfiguredException.class);
        verify(strategy, never()).generateSql(any(), any(), any(), any(), any());
    }

    @Test
    void generateSqlPropagatesRateLimitExceeded() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(true, aiConfigId)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        org.mockito.Mockito.doThrow(new AiRateLimitExceededException(30, 60))
                .when(aiRateLimiter).enforce(organizationId);

        assertThatThrownBy(() -> service.generateSql(datasourceId, "x", userId, organizationId, false))
                .isInstanceOf(AiRateLimitExceededException.class);
        verify(strategy, never()).generateSql(any(), any(), any(), any(), any());
    }

    @Test
    void generateSqlThrowsWhenAiConfigBelongsToAnotherOrg() {
        var foreign = aiConfigEntity();
        foreign.setOrganizationId(UUID.randomUUID());
        when(aiConfigRepository.findById(aiConfigId)).thenReturn(Optional.of(foreign));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(true, aiConfigId)));

        assertThatThrownBy(() -> service.generateSql(datasourceId, "x", userId, organizationId, false))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("does not belong");
    }
}
