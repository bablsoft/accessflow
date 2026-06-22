package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.PushSubscriptionService;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushSubscriptionEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushSubscriptionRepository;
import com.bablsoft.accessflow.notifications.internal.push.PushVapidKeyProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Default {@link PushSubscriptionService}. Subscriptions are upserted by their unique W3C Push API
 * endpoint so re-subscribing the same browser updates the keys in place and re-homes the row to the
 * current user (a shared device that switches accounts).
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DefaultPushSubscriptionService implements PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final PushVapidKeyProvider vapidKeyProvider;

    @Override
    @Transactional
    public void subscribe(PushSubscriptionCommand command) {
        var entity = repository.findByEndpoint(command.endpoint())
                .orElseGet(() -> {
                    var fresh = new PushSubscriptionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setCreatedAt(Instant.now());
                    return fresh;
                });
        entity.setUserId(command.userId());
        entity.setOrganizationId(command.organizationId());
        entity.setEndpoint(command.endpoint());
        entity.setP256dhKey(command.p256dhKey());
        entity.setAuthKey(command.authKey());
        entity.setUserAgent(command.userAgent());
        entity.setLastUsedAt(Instant.now());
        repository.save(entity);
        log.debug("Stored push subscription for user {}", command.userId());
    }

    @Override
    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        repository.deleteByUserIdAndEndpoint(userId, endpoint);
    }

    @Override
    public String vapidPublicKey() {
        return vapidKeyProvider.resolve().publicKeyBase64Url();
    }
}
