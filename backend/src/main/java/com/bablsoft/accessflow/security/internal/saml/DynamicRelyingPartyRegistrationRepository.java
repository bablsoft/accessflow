package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import com.bablsoft.accessflow.security.internal.saml.events.SamlConfigUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SAML SP registration backed by the {@code saml_config} table — one registration per active
 * organization (registrationId = {@value #REGISTRATION_ID}, since AccessFlow is single-org).
 *
 * Builds a fresh {@link RelyingPartyRegistration} on demand by:
 * - Reading the active config row;
 * - Bootstrapping asserting-party metadata from {@code idp_metadata_url};
 * - Adding the IdP signing certificate (decrypted) as a verification credential;
 * - Adding the SP signing keypair from {@link SamlSpKeyProvider} as a signing credential.
 *
 * Caches the assembled registration in memory; evicts on {@link SamlConfigUpdatedEvent} so the
 * next request reads fresh state. Returns {@code null} when SAML is not configured / inactive,
 * which causes Spring's filters to skip and the public {@code /auth/saml/enabled} endpoint to
 * report false.
 */
@Component
public class DynamicRelyingPartyRegistrationRepository
        implements RelyingPartyRegistrationRepository, Iterable<RelyingPartyRegistration> {

    public static final String REGISTRATION_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(DynamicRelyingPartyRegistrationRepository.class);

    private final SamlConfigRepository samlConfigRepository;
    private final CredentialEncryptionService encryptionService;
    private final SamlSpKeyProvider spKeyProvider;
    private final OrganizationLookupService organizationLookupService;
    private final AtomicReference<RelyingPartyRegistration> cached = new AtomicReference<>();

    public DynamicRelyingPartyRegistrationRepository(SamlConfigRepository samlConfigRepository,
                                                     CredentialEncryptionService encryptionService,
                                                     SamlSpKeyProvider spKeyProvider,
                                                     OrganizationLookupService organizationLookupService) {
        this.samlConfigRepository = samlConfigRepository;
        this.encryptionService = encryptionService;
        this.spKeyProvider = spKeyProvider;
        this.organizationLookupService = organizationLookupService;
    }

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        var hit = cached.get();
        if (hit != null) {
            return hit;
        }
        try {
            var built = build();
            if (built != null) {
                cached.set(built);
            }
            return built;
        } catch (RuntimeException ex) {
            log.debug("RelyingPartyRegistration build skipped ({}); SAML is effectively disabled until config is seeded",
                    ex.getMessage());
            return null;
        }
    }

    @Override
    public Iterator<RelyingPartyRegistration> iterator() {
        var registration = findByRegistrationId(REGISTRATION_ID);
        return registration != null ? List.of(registration).iterator() : List.<RelyingPartyRegistration>of().iterator();
    }

    /**
     * Evicts the in-memory registration so the next call rebuilds from the database. Fires on
     * every SAML config save — admins can flip {@code active}, rotate the IdP cert, or change
     * attribute mappings without restarting the app.
     */
    @EventListener
    public void onSamlConfigUpdated(SamlConfigUpdatedEvent event) {
        log.debug("Evicting cached RelyingPartyRegistration for organization {}", event.organizationId());
        cached.set(null);
    }

    @Transactional(readOnly = true)
    protected RelyingPartyRegistration build() {
        var organizationId = organizationLookupService.singleOrganization();
        var entity = samlConfigRepository.findByOrganizationId(organizationId).orElse(null);
        if (entity == null || !entity.isActive()) {
            return null;
        }
        if (entity.getIdpMetadataUrl() == null || entity.getIdpMetadataUrl().isBlank()) {
            log.warn("SAML config active for org {} but idp_metadata_url is empty — skipping", organizationId);
            return null;
        }
        if (entity.getSigningCertPem() == null || entity.getSigningCertPem().isBlank()) {
            log.warn("SAML config active for org {} but signing_cert_pem is empty — skipping", organizationId);
            return null;
        }
        var spKey = spKeyProvider.resolve(organizationId);
        var idpCertPem = encryptionService.decrypt(entity.getSigningCertPem());
        var idpCert = SamlSpKeyProvider.parseCertificate(idpCertPem);
        var idpVerification = Saml2X509Credential.verification(idpCert);
        var spSigning = Saml2X509Credential.signing(spKey.privateKey(), spKey.certificate());

        var builder = RelyingPartyRegistrations
                .fromMetadataLocation(entity.getIdpMetadataUrl())
                .registrationId(REGISTRATION_ID);
        if (entity.getSpEntityId() != null && !entity.getSpEntityId().isBlank()) {
            builder.entityId(entity.getSpEntityId());
        }
        if (entity.getAcsUrl() != null && !entity.getAcsUrl().isBlank()) {
            builder.assertionConsumerServiceLocation(entity.getAcsUrl());
        } else {
            builder.assertionConsumerServiceLocation("{baseUrl}/api/v1/auth/saml/acs");
        }
        builder.signingX509Credentials(c -> c.add(spSigning));
        builder.assertingPartyMetadata(ap -> {
            ap.verificationX509Credentials(c -> c.add(idpVerification));
            if (entity.getIdpEntityId() != null && !entity.getIdpEntityId().isBlank()) {
                ap.entityId(entity.getIdpEntityId());
            }
        });
        return builder.build();
    }
}
