package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.ai.api.ApiCallAnalyzer;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiRequestPermissionException;
import com.bablsoft.accessflow.apigov.api.ApiRequestValidationException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiAssistService implements ApiAssistService {

    private final ApiConnectorRepository connectorRepository;
    private final ApiConnectorUserPermissionRepository permissionRepository;
    private final ApiSchemaService schemaService;
    private final ApiCallAnalyzer apiCallAnalyzer;

    @Override
    @Transactional(readOnly = true)
    public ApiAiPreview analyze(UUID connectorId, UUID organizationId, UUID userId, boolean admin,
                                AnalyzeInput input) {
        var connector = resolveAccessible(connectorId, organizationId, userId, admin);
        var schemaContext = renderSchema(connector, organizationId);
        var result = apiCallAnalyzer.analyzeApiCall(new ApiCallAnalyzer.ApiCallAnalysisInput(
                organizationId, connector.getAiConfigId(), connector.getProtocol().name(),
                input.verb(), input.requestPath(), input.requestBody(), schemaContext, input.language()));
        var issues = result.issues().stream()
                .map(i -> i.category() + ": " + i.message())
                .toList();
        return new ApiAiPreview(result.riskScore(), result.riskLevel(), result.summary(), issues);
    }

    @Override
    @Transactional(readOnly = true)
    public GeneratedApiCallView generate(UUID connectorId, UUID organizationId, UUID userId, boolean admin,
                                         String prompt, String language) {
        var connector = resolveAccessible(connectorId, organizationId, userId, admin);
        if (!connector.isTextToApiEnabled()) {
            throw new ApiRequestValidationException("Text-to-API is not enabled for this connector");
        }
        var schemaContext = renderSchema(connector, organizationId);
        if (schemaContext == null) {
            throw new ApiRequestValidationException("Text-to-API requires a parsed schema on the connector");
        }
        var generated = apiCallAnalyzer.generateApiCall(new ApiCallAnalyzer.ApiCallGenerationInput(
                organizationId, connector.getAiConfigId(), connector.getProtocol().name(), prompt,
                schemaContext, language));
        return new GeneratedApiCallView(generated.draft());
    }

    private ApiConnectorEntity resolveAccessible(UUID connectorId, UUID organizationId, UUID userId,
                                                 boolean admin) {
        var connector = connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
        if (!admin) {
            var permitted = permissionRepository.findByConnectorIdAndUserId(connectorId, userId)
                    .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(Instant.now()))
                    .map(p -> p.isCanRead() || p.isCanWrite())
                    .orElse(false);
            if (!permitted) {
                throw new ApiRequestPermissionException("No access to this connector");
            }
        }
        return connector;
    }

    private String renderSchema(ApiConnectorEntity connector, UUID organizationId) {
        List<ApiOperation> ops = schemaService.listOperations(connector.getId(), organizationId);
        if (ops.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var op : ops) {
            sb.append(op.verb()).append(' ').append(op.path())
                    .append(op.write() ? " [write]" : " [read]").append('\n');
        }
        return sb.toString();
    }
}
