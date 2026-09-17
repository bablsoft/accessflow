package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountLookupService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountView;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultServiceAccountLookupService implements ServiceAccountLookupService {

    private final ServiceAccountRepository repository;

    @Override
    public Optional<ServiceAccountView> findByUserId(UUID userId) {
        return repository.findById(userId).map(ServiceAccountViews::toView);
    }

    @Override
    public List<ServiceAccountView> listByOrganization(UUID organizationId) {
        return repository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .map(ServiceAccountViews::toView)
                .toList();
    }
}
