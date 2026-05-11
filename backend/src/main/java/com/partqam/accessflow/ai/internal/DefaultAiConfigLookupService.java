package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiConfigLookupService;
import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiConfigLookupService implements AiConfigLookupService {

    private final AiConfigRepository aiConfigRepository;
    private final DatasourceLookupService datasourceLookupService;

    @Override
    @Transactional(readOnly = true)
    public boolean hasUsableAiAnalysisConfiguredDatasource(UUID organizationId) {
        if (organizationId == null) {
            return false;
        }
        var boundConfigIds = datasourceLookupService
                .findActiveAiAnalysisAiConfigIdsByOrganization(organizationId);
        if (boundConfigIds.isEmpty()) {
            return false;
        }
        return aiConfigRepository.findAllById(boundConfigIds).stream()
                .anyMatch(DefaultAiConfigLookupService::isUsable);
    }

    private static boolean isUsable(AiConfigEntity entity) {
        if (entity.getProvider() == AiProviderType.OLLAMA) {
            return true;
        }
        return entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank();
    }
}
