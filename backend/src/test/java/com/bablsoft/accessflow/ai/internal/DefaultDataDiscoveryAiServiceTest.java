package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.DataDiscoveryAiService.DiscoveryColumnContext;
import com.bablsoft.accessflow.ai.api.DataDiscoveryAiService.DiscoveryTableContext;
import com.bablsoft.accessflow.core.api.DataClassification;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class DefaultDataDiscoveryAiServiceTest {

    private final AiAnalyzerStrategyHolder holder = mock(AiAnalyzerStrategyHolder.class);
    private final ObjectProvider<AiAnalyzerStrategyHolder> holderProvider = mock(ObjectProvider.class);
    private final DefaultDataDiscoveryAiService service =
            new DefaultDataDiscoveryAiService(holderProvider, new ObjectMapper());

    private final UUID orgId = UUID.randomUUID();

    DefaultDataDiscoveryAiServiceTest() {
        when(holderProvider.getObject()).thenReturn(holder);
    }

    private static DiscoveryTableContext context() {
        return new DiscoveryTableContext("public.users", List.of(
                new DiscoveryColumnContext("national_id", "varchar", List.of("*** ** ****")),
                new DiscoveryColumnContext("name", "varchar", List.of("xxxxx"))));
    }

    @Test
    void returnsEmptyForNullOrgOrEmptyContext() {
        assertThat(service.classifyColumns(null, context())).isEmpty();
        assertThat(service.classifyColumns(orgId, null)).isEmpty();
        assertThat(service.classifyColumns(orgId,
                new DiscoveryTableContext("t", List.of()))).isEmpty();
        verifyNoInteractions(holder);
    }

    @Test
    void returnsEmptyWhenHolderYieldsNothing() {
        when(holder.classifyDiscoveryColumns(eq(orgId), anyString())).thenReturn(Optional.empty());

        assertThat(service.classifyColumns(orgId, context())).isEmpty();
    }

    @Test
    void parsesWellFormedResponse() {
        when(holder.classifyDiscoveryColumns(eq(orgId), anyString())).thenReturn(Optional.of("""
                {"columns":[{"column":"national_id","classification":"PII","confidence":85,\
                "rationale":"Looks like a national identifier."}]}"""));

        var suggestions = service.classifyColumns(orgId, context());

        assertThat(suggestions).hasSize(1);
        var suggestion = suggestions.getFirst();
        assertThat(suggestion.columnName()).isEqualTo("national_id");
        assertThat(suggestion.classification()).isEqualTo(DataClassification.PII);
        assertThat(suggestion.confidence()).isEqualTo(85);
        assertThat(suggestion.rationale()).isEqualTo("Looks like a national identifier.");
    }

    @Test
    void toleratesMarkdownCodeFences() {
        when(holder.classifyDiscoveryColumns(eq(orgId), anyString())).thenReturn(Optional.of("""
                ```json
                {"columns":[{"column":"national_id","classification":"SENSITIVE","confidence":60}]}
                ```"""));

        var suggestions = service.classifyColumns(orgId, context());

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().classification()).isEqualTo(DataClassification.SENSITIVE);
        assertThat(suggestions.getFirst().rationale()).isNull();
    }

    @Test
    void dropsUnknownColumnsAndClassificationsAndClampsConfidence() {
        when(holder.classifyDiscoveryColumns(eq(orgId), anyString())).thenReturn(Optional.of("""
                {"columns":[
                  {"column":"not_a_column","classification":"PII","confidence":90},
                  {"column":"name","classification":"TOP_SECRET","confidence":90},
                  {"column":"name","classification":"pii","confidence":250},
                  {"column":"","classification":"PII","confidence":10},
                  {"column":"national_id","classification":"PHI","confidence":"high"}
                ]}"""));

        var suggestions = service.classifyColumns(orgId, context());

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).columnName()).isEqualTo("name");
        assertThat(suggestions.get(0).classification()).isEqualTo(DataClassification.PII);
        assertThat(suggestions.get(0).confidence()).isEqualTo(100);
        assertThat(suggestions.get(1).columnName()).isEqualTo("national_id");
        assertThat(suggestions.get(1).confidence()).isZero();
    }

    @Test
    void returnsEmptyOnMalformedJsonOrMissingColumnsArray() {
        when(holder.classifyDiscoveryColumns(eq(orgId), anyString()))
                .thenReturn(Optional.of("this is not json"));
        assertThat(service.classifyColumns(orgId, context())).isEmpty();

        when(holder.classifyDiscoveryColumns(eq(orgId), anyString()))
                .thenReturn(Optional.of("{\"columns\":{\"column\":\"name\"}}"));
        assertThat(service.classifyColumns(orgId, context())).isEmpty();
    }

    @Test
    void promptCarriesTableColumnsTypesAndRedactedSamples() {
        when(holder.classifyDiscoveryColumns(eq(orgId), anyString())).thenReturn(Optional.empty());

        service.classifyColumns(orgId, context());

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(holder).classifyDiscoveryColumns(eq(orgId), prompt.capture());
        assertThat(prompt.getValue())
                .contains("Table: public.users")
                .contains("national_id (varchar) samples: *** ** ****")
                .contains("- name (varchar) samples: xxxxx");
    }
}
