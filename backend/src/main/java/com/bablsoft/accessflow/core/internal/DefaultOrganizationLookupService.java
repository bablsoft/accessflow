package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.SingleOrganizationUnavailableException;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultOrganizationLookupService implements OrganizationLookupService {

    private final OrganizationRepository organizationRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public UUID singleOrganization() {
        var rows = organizationRepository.findAll();
        if (rows.isEmpty()) {
            throw new SingleOrganizationUnavailableException(
                    messageSource.getMessage("error.organization.none_present", null,
                            LocaleContextHolder.getLocale()));
        }
        if (rows.size() > 1) {
            throw new SingleOrganizationUnavailableException(
                    messageSource.getMessage("error.organization.more_than_one", null,
                            LocaleContextHolder.getLocale()));
        }
        return rows.getFirst().getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findNameById(UUID organizationId) {
        return organizationRepository.findById(organizationId).map(OrganizationEntity::getName);
    }
}
