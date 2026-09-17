package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-side access to service-account detail rows (#868). */
public interface ServiceAccountLookupService {

    /** The detail row for a user, empty when the user is a person (or unknown). */
    Optional<ServiceAccountView> findByUserId(UUID userId);

    /** Every service account of an organization, oldest first. */
    List<ServiceAccountView> listByOrganization(UUID organizationId);
}
