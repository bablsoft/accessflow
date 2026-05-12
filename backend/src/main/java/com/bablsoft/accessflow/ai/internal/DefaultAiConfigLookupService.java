package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigLookupService;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiConfigLookupService implements AiConfigLookupService {

    private final AiConfigRepository aiConfigRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean hasAnyUsableAiConfig(UUID organizationId) {
        if (organizationId == null) {
            return false;
        }
        return aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(organizationId).stream()
                .anyMatch(DefaultAiConfigLookupService::isUsable);
    }

    private static boolean isUsable(AiConfigEntity entity) {
        if (entity.getProvider() == AiProviderType.OLLAMA) {
            return true;
        }
        return entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank();
    }
}
