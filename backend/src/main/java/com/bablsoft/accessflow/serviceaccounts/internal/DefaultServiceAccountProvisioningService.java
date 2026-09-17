package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountView;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultServiceAccountProvisioningService implements ServiceAccountProvisioningService {

    private final ServiceAccountRepository repository;
    private final UserAdminService userAdminService;

    @Override
    @Transactional
    public ServiceAccountView ensureRegistered(UUID organizationId, UUID userId,
                                               ServiceAccountSource managedBy) {
        // The discriminator first: it also proves the user exists in this organization
        // (UserNotFoundException otherwise), so a detail row can never point at a foreign user.
        userAdminService.setPrincipalType(userId, organizationId, PrincipalType.SERVICE_ACCOUNT);

        var entity = repository.findById(userId).orElseGet(() -> {
            var fresh = new ServiceAccountEntity();
            fresh.setUserId(userId);
            fresh.setOrganizationId(organizationId);
            return fresh;
        });
        // Only managed_by is re-asserted; every other field is UI-owned and must survive a re-run.
        entity.setManagedBy(managedBy);
        return ServiceAccountViews.toView(repository.save(entity));
    }
}
