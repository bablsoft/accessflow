package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.events.SqlReviewCustomRuleChangedEvent;
import com.bablsoft.accessflow.sqlreview.internal.config.SqlReviewProperties;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewCustomRuleRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.CustomSqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlRuleSourceTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final String DBLINK = "{\"type\":\"function_called\",\"names\":[\"dblink\"]}";

    @Mock
    private SqlReviewCustomRuleRepository repository;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
    private final SqlRuleCatalog catalog = new SqlRuleCatalog();
    private SqlRuleSource source;

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        source = new SqlRuleSource(catalog, repository, SqlRuleSources.codec(messages),
                new SqlRuleConditionValidator(messages), clock, new SqlReviewProperties(Duration.ofMinutes(1)));
    }

    private static SqlReviewCustomRuleEntity row(UUID org, String ruleId, String condition) {
        var row = SqlRuleSources.row(ruleId, SqlReviewSeverity.BLOCK, condition);
        row.setOrganizationId(org);
        return row;
    }

    @Test
    void appendsTheOrganizationsCustomRulesAfterTheBuiltIns() {
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A))
                .thenReturn(List.of(row(ORG_A, "custom_a", DBLINK), row(ORG_A, "custom_b", DBLINK)));

        var rules = source.rules(ORG_A);

        assertThat(rules).hasSize(catalog.rules().size() + 2);
        assertThat(rules.subList(0, catalog.rules().size())).containsExactlyElementsOf(catalog.rules());
        assertThat(rules.subList(catalog.rules().size(), rules.size())).extracting(SqlRule::ruleId)
                .containsExactly("custom_a", "custom_b");
        assertThat(rules.get(rules.size() - 1)).isInstanceOf(CustomSqlRule.class);
    }

    @Test
    void organizationsNeverSeeEachOthersRules() {
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A))
                .thenReturn(List.of(row(ORG_A, "custom_a", DBLINK)));
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_B))
                .thenReturn(List.of(row(ORG_B, "custom_b", DBLINK)));

        assertThat(source.rules(ORG_A)).extracting(SqlRule::ruleId).contains("custom_a").doesNotContain("custom_b");
        assertThat(source.rules(ORG_B)).extracting(SqlRule::ruleId).contains("custom_b").doesNotContain("custom_a");
        assertThat(source.byId(ORG_A, "custom_b")).isEmpty();
        assertThat(source.byId(ORG_B, "custom_b")).isPresent();
    }

    @Test
    void byIdResolvesBuiltInsWithoutLoadingCustomRules() {
        assertThat(source.byId(ORG_A, "select_star")).containsSame(catalog.byId("select_star").orElseThrow());
        assertThat(source.byId(ORG_A, "nope")).isEmpty();
        assertThat(source.byId(ORG_A, null)).isEmpty();
        verify(repository, times(0)).findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A);
    }

    @Test
    void disabledMalformedAndUnprefixedRowsAreNeverEvaluated() {
        var disabled = row(ORG_A, "custom_disabled", DBLINK);
        disabled.setEnabled(false);
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A)).thenReturn(List.of(
                disabled,
                row(ORG_A, "custom_bad_json", "{not json"),
                row(ORG_A, "custom_empty_list", "{\"type\":\"function_called\",\"names\":[]}"),
                row(ORG_A, "custom_bad_regex", "{\"type\":\"sql_matches\",\"pattern\":\"(\",\"ignore_case\":false}"),
                row(ORG_A, "select_star", DBLINK),
                row(ORG_A, "custom_good", DBLINK)));

        assertThat(source.rules(ORG_A)).filteredOn(rule -> rule instanceof CustomSqlRule)
                .extracting(SqlRule::ruleId).containsExactly("custom_good");
        assertThat(source.byId(ORG_A, "custom_disabled")).isEmpty();
    }

    @Test
    void customRuleExistsAsksTheRepositoryOnlyForCustomIds() {
        when(repository.existsByOrganizationIdAndRuleId(ORG_A, "custom_off")).thenReturn(true);

        assertThat(source.customRuleExists(ORG_A, "custom_off")).isTrue();
        assertThat(source.customRuleExists(ORG_A, "custom_gone")).isFalse();
        assertThat(source.customRuleExists(ORG_A, "select_star")).isFalse();
        verify(repository, times(0)).existsByOrganizationIdAndRuleId(ORG_A, "select_star");
    }

    @Test
    void cachesPerOrganizationUntilTheChangeEvent() {
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A))
                .thenReturn(List.of(row(ORG_A, "custom_a", DBLINK)))
                .thenReturn(List.of());
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_B))
                .thenReturn(List.of(row(ORG_B, "custom_b", DBLINK)));

        assertThat(source.byId(ORG_A, "custom_a")).isPresent();
        assertThat(source.byId(ORG_B, "custom_b")).isPresent();
        assertThat(source.byId(ORG_A, "custom_a")).isPresent();
        verify(repository, times(1)).findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A);

        source.onCustomRuleChanged(new SqlReviewCustomRuleChangedEvent(ORG_A));

        assertThat(source.byId(ORG_A, "custom_a")).isEmpty();
        verify(repository, times(2)).findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A);
        // Only the named organization was evicted.
        assertThat(source.byId(ORG_B, "custom_b")).isPresent();
        verify(repository, times(1)).findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_B);
    }

    @Test
    void aLoadThatRacedAnEvictionIsServedButNeverCached() {
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A))
                .thenAnswer(invocation -> {
                    // The writer commits and evicts while these (now stale) rows are being read.
                    source.onCustomRuleChanged(new SqlReviewCustomRuleChangedEvent(ORG_A));
                    return List.of(row(ORG_A, "custom_stale", DBLINK));
                })
                .thenReturn(List.of());

        assertThat(source.byId(ORG_A, "custom_stale")).isPresent();
        assertThat(source.byId(ORG_A, "custom_stale")).isEmpty();
        verify(repository, times(2)).findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A);
    }

    @Test
    void reloadsAfterTheTtlSoOtherReplicasConverge() {
        when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A))
                .thenReturn(List.of(row(ORG_A, "custom_a", DBLINK)))
                .thenReturn(List.of());

        assertThat(source.byId(ORG_A, "custom_a")).isPresent();
        clock.advance(Duration.ofSeconds(59));
        assertThat(source.byId(ORG_A, "custom_a")).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(source.byId(ORG_A, "custom_a")).isEmpty();
        verify(repository, times(2)).findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(ORG_A);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
