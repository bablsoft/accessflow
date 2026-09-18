package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.McpToolName;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountToolPolicyService;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

/**
 * Allow-list decision (#872). The detail row's presence is the HUMAN / SERVICE_ACCOUNT
 * discriminator here: {@code ensureRegistered} creates it in the same transaction that flips
 * {@code users.principal_type}, so a person never has one. The catalog check runs first and
 * without a database hit — an unknown name is denied for every caller.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultServiceAccountToolPolicyService implements ServiceAccountToolPolicyService {

    private final ServiceAccountRepository repository;

    @Override
    public boolean isAllowed(UUID userId, String toolName) {
        if (McpToolName.fromToolName(toolName).isEmpty()) {
            return false;
        }
        return repository.findById(userId)
                .map(account -> {
                    var allowList = account.getMcpToolAllowList();
                    return allowList == null || Arrays.asList(allowList).contains(toolName);
                })
                .orElse(true);
    }
}
