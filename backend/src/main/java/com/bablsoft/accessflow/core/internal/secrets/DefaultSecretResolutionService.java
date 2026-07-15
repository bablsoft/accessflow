package com.bablsoft.accessflow.core.internal.secrets;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.InvalidSecretReferenceException;
import com.bablsoft.accessflow.core.api.SecretProviderDisabledException;
import com.bablsoft.accessflow.core.api.SecretResolutionException;
import com.bablsoft.accessflow.core.api.SecretResolutionService;
import com.bablsoft.accessflow.core.events.SecretReferenceResolutionFailedEvent;
import com.bablsoft.accessflow.core.events.SecretReferenceResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link SecretResolutionService} (AF-448). Non-reference values fall straight through
 * to local AES-256-GCM decryption (no event). References are parsed, dispatched to the enabled
 * {@link SecretStore} and fetched on every call — resolved plaintext is never cached here; the
 * store SDKs own auth-token refresh. Every external resolve publishes a success or failure
 * event for the audit module; events carry the reference (not a secret) and never the value.
 */
@Service
class DefaultSecretResolutionService implements SecretResolutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSecretResolutionService.class);
    private static final List<String> PROVIDER_ORDER = List.of(
            SecretReference.PROVIDER_VAULT, SecretReference.PROVIDER_AWS, SecretReference.PROVIDER_AZURE);

    private final Map<String, SecretStore> stores;
    private final CredentialEncryptionService encryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    DefaultSecretResolutionService(List<SecretStore> stores,
                                   CredentialEncryptionService encryptionService,
                                   ApplicationEventPublisher eventPublisher,
                                   MessageSource messageSource) {
        var byProvider = new LinkedHashMap<String, SecretStore>();
        for (var store : stores) {
            byProvider.put(store.providerId(), store);
        }
        this.stores = Map.copyOf(byProvider);
        this.encryptionService = encryptionService;
        this.eventPublisher = eventPublisher;
        this.messageSource = messageSource;
    }

    @Override
    public String resolve(String storedCredential) {
        return resolve(storedCredential, null, null);
    }

    @Override
    public String resolve(String storedCredential, UUID datasourceId, UUID organizationId) {
        if (!isReference(storedCredential)) {
            return encryptionService.decrypt(storedCredential);
        }
        String provider = storedCredential.substring(0, storedCredential.indexOf(':'));
        SecretReference reference;
        try {
            reference = SecretReference.parse(storedCredential);
        } catch (InvalidSecretReferenceException ex) {
            // Only reachable when the stored value drifted after write-time validation.
            throw failure(provider, storedCredential, datasourceId, organizationId,
                    "malformed reference", ex);
        }
        SecretStore store = stores.get(provider);
        if (store == null) {
            throw failure(provider, storedCredential, datasourceId, organizationId,
                    "provider not enabled", null);
        }
        String value;
        try {
            value = store.fetch(reference);
        } catch (SecretStoreFetchException ex) {
            throw failure(provider, storedCredential, datasourceId, organizationId,
                    ex.getMessage(), ex);
        }
        eventPublisher.publishEvent(new SecretReferenceResolvedEvent(
                provider, storedCredential, datasourceId, organizationId));
        return value;
    }

    @Override
    public boolean isReference(String value) {
        return SecretReference.isReference(value);
    }

    @Override
    public void validateReference(String value) {
        var reference = SecretReference.parse(value);
        if (!stores.containsKey(reference.provider())) {
            throw new SecretProviderDisabledException(reference.provider());
        }
    }

    @Override
    public List<String> enabledProviders() {
        return PROVIDER_ORDER.stream().filter(stores::containsKey).toList();
    }

    private SecretResolutionException failure(String provider, String reference,
                                              UUID datasourceId, UUID organizationId,
                                              String error, Throwable cause) {
        log.error("Secret reference resolution failed (provider={}, reference={}): {}",
                provider, reference, error, cause);
        eventPublisher.publishEvent(new SecretReferenceResolutionFailedEvent(
                provider, reference, datasourceId, organizationId, error));
        String message = messageSource.getMessage("error.secret_resolution_failed",
                new Object[]{provider}, LocaleContextHolder.getLocale());
        return cause == null
                ? new SecretResolutionException(provider, reference, message)
                : new SecretResolutionException(provider, reference, message, cause);
    }
}
