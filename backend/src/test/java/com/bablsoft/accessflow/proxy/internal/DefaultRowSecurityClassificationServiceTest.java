package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultRowSecurityClassificationServiceTest {

    @Mock QueryEngineCatalog engineCatalog;
    @Mock RowSecurityRewriter rowSecurityRewriter;

    private DefaultRowSecurityClassificationService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        // Register under the JVM default too: LocaleContextHolder falls back to it, and CI's
        // default locale is not necessarily Locale.ENGLISH.
        messages.addMessage("error.policy_simulation.engine_unavailable", Locale.ENGLISH,
                "engine unavailable");
        messages.addMessage("error.policy_simulation.engine_unavailable", Locale.getDefault(),
                "engine unavailable");
        service = new DefaultRowSecurityClassificationService(engineCatalog, rowSecurityRewriter,
                messages);
    }

    private RowSecurityDirective directive(RowSecurityOperator operator, List<Object> values) {
        return new RowSecurityDirective(policyId, "orders", "tenant", operator, values);
    }

    private RowSecurityClassification classifyRelational(List<RowSecurityDirective> directives) {
        when(engineCatalog.isEngineManaged(DbType.POSTGRESQL)).thenReturn(false);
        return service.classify(datasourceId, DbType.POSTGRESQL, "SELECT * FROM orders", directives);
    }

    // ---- short circuit --------------------------------------------------------------------------

    @Test
    void noDirectivesIsNotApplicableAndNeverTouchesTheRewriter() {
        var result = service.classify(datasourceId, DbType.POSTGRESQL, "SELECT 1", List.of());

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        verify(rowSecurityRewriter, never()).rewrite(any(), any());
    }

    @Test
    void nullDirectivesAreTreatedAsNone() {
        assertThat(service.classify(datasourceId, DbType.POSTGRESQL, "SELECT 1", null).outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    // ---- relational path ------------------------------------------------------------------------

    @Test
    void relationalRewriteThatAppliesNoPolicyIsNotApplicable() {
        when(rowSecurityRewriter.rewrite(any(), any()))
                .thenReturn(new RowSecurityRewriter.RewriteResult("SELECT 1", List.of(), Set.of()));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.EQUALS, List.of("a"))));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("jdbc");
    }

    @Test
    void relationalRewriteThatSplicesAPredicateIsApplied() {
        when(rowSecurityRewriter.rewrite(any(), any())).thenReturn(
                new RowSecurityRewriter.RewriteResult("SELECT 1", List.of("a"), Set.of(policyId)));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.EQUALS, List.of("a"))));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(policyId);
    }

    @Test
    void anAppliedDirectiveWithNoValuesIsDenyAll() {
        when(rowSecurityRewriter.rewrite(any(), any())).thenReturn(
                new RowSecurityRewriter.RewriteResult("SELECT 1", List.of(), Set.of(policyId)));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.IN, List.of())));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.DENY_ALL);
        assertThat(result.appliedPolicyIds()).containsExactly(policyId);
    }

    @Test
    void unaryIsNullIsAppliedNotDenyAll() {
        when(rowSecurityRewriter.rewrite(any(), any())).thenReturn(
                new RowSecurityRewriter.RewriteResult("SELECT 1", List.of(), Set.of(policyId)));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.IS_NULL, List.of())));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
    }

    @Test
    void aValuelessDirectiveThatDidNotTakeEffectDoesNotMakeItDenyAll() {
        // The rewriter applied a different policy; this one never matched, so it cannot deny.
        when(rowSecurityRewriter.rewrite(any(), any())).thenReturn(
                new RowSecurityRewriter.RewriteResult("SELECT 1", List.of(), Set.of(UUID.randomUUID())));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.IN, List.of())));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
    }

    @Test
    void anUnrewritableShapeFailsClosedAndKeepsTheRewriterMessage() {
        when(rowSecurityRewriter.rewrite(any(), any()))
                .thenThrow(new UnrewritableRowSecurityException("UNION over a protected table"));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.EQUALS, List.of("a"))));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isEqualTo("UNION over a protected table");
    }

    @Test
    void unparseableSqlIsUnknownRatherThanUnaffected() {
        when(rowSecurityRewriter.rewrite(any(), any()))
                .thenThrow(new InvalidSqlException("cannot parse"));

        var result = classifyRelational(List.of(directive(RowSecurityOperator.EQUALS, List.of("a"))));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isEqualTo("cannot parse");
    }

    // ---- engine-managed path --------------------------------------------------------------------

    @Test
    void engineManagedDialectsDelegateToThePlugin() {
        var engine = mock(QueryEngine.class);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        when(engine.classifyRowSecurity(any(QueryEngineRowSecurityRequest.class)))
                .thenReturn(RowSecurityClassification.failClosed("mongodb", "insert into policied"));

        var result = service.classify(datasourceId, DbType.MONGODB, "db.orders.insertOne({})",
                List.of(directive(RowSecurityOperator.EQUALS, List.of("a"))));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.engineId()).isEqualTo("mongodb");
        verify(rowSecurityRewriter, never()).rewrite(any(), any());
    }

    @Test
    void anUnresolvablePluginDegradesToUnknownInsteadOfFailingTheSimulation() {
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB))
                .thenThrow(new DriverResolutionException(DbType.MONGODB,
                        DriverResolutionException.Reason.OFFLINE_CACHE_MISS, "offline"));

        var result = service.classify(datasourceId, DbType.MONGODB, "db.orders.find({})",
                List.of(directive(RowSecurityOperator.EQUALS, List.of("a"))));

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.engineId()).isEqualTo("mongodb");
        assertThat(result.reason()).isEqualTo("engine unavailable");
    }

    @Test
    void theShortCircuitStampsTheEngineIdOfAnEngineManagedDialect() {
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);

        var result = service.classify(datasourceId, DbType.MONGODB, "db.orders.find({})", List.of());

        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("mongodb");
    }
}
