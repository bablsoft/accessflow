package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.ApiCallAnalyzer;
import com.bablsoft.accessflow.ai.api.GeneratedApiCall;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRequestValidationException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiAssistServiceTest {

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock private ApiSchemaService schemaService;
    @Mock private ApiCallAnalyzer apiCallAnalyzer;

    private DefaultApiAssistService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiAssistService(connectorRepository, permissionResolver, schemaService,
                apiCallAnalyzer);
    }

    private ApiConnectorEntity connector(boolean textToApi) {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setOrganizationId(orgId);
        c.setProtocol(ApiProtocol.REST);
        c.setTextToApiEnabled(textToApi);
        return c;
    }

    @Test
    void analyzeReturnsPreviewForAdmin() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector(false)));
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of(
                new ApiOperation("listPets", "GET", "/pets", null, false, null, null)));
        when(apiCallAnalyzer.analyzeApiCall(any())).thenReturn(new AiAnalysisResult(70, RiskLevel.HIGH,
                "risky", List.of(), false, null, AiProviderType.ANTHROPIC, "claude", 1, 1, List.of()));

        var preview = service.analyze(connectorId, orgId, userId, true,
                new ApiAssistService.AnalyzeInput(null, "GET", "/pets", null, "en"));

        assertThat(preview.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(preview.riskScore()).isEqualTo(70);
    }

    @Test
    void generateRejectedWhenTextToApiDisabled() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector(false)));

        assertThatThrownBy(() -> service.generate(connectorId, orgId, userId, true, "do a thing", "en"))
                .isInstanceOf(ApiRequestValidationException.class);
    }

    @Test
    void generateRejectedWhenNoSchema() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector(true)));
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(connectorId, orgId, userId, true, "do a thing", "en"))
                .isInstanceOf(ApiRequestValidationException.class);
    }

    @Test
    void generateReturnsDraftWhenEnabledWithSchema() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector(true)));
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of(
                new ApiOperation("createPet", "POST", "/pets", null, true, null, null)));
        when(apiCallAnalyzer.generateApiCall(any())).thenReturn(
                new GeneratedApiCall("POST /pets {}", AiProviderType.OPENAI, "gpt", 1, 1));

        var draft = service.generate(connectorId, orgId, userId, true, "create a pet", "en");

        assertThat(draft.draft()).isEqualTo("POST /pets {}");
    }
}
