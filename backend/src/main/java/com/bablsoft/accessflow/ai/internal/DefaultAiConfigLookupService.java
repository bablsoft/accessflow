package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigLookupService;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
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
        return AiConfigUsability.isUsable(entity.getProvider(), entity.getApiKeyEncrypted());
    }
}
