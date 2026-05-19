package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.NotificationChannelSpec;
import com.bablsoft.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.api.NotificationChannelService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelView;
import com.bablsoft.accessflow.notifications.api.UpdateNotificationChannelCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationChannelReconciler {

    private final NotificationChannelService notificationChannelService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public Map<String, UUID> reconcile(UUID organizationId, List<NotificationChannelSpec> specs) {
        var byName = new HashMap<String, UUID>();
        for (var spec : specs) {
            var id = applyOne(organizationId, spec);
            byName.put(spec.name(), id);
        }
        return Map.copyOf(byName);
    }

    private UUID applyOne(UUID organizationId, NotificationChannelSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("Notification channel spec is missing 'name'");
        }
        if (spec.channelType() == null) {
            throw new IllegalStateException(
                    "Notification channel '%s' is missing 'channelType'".formatted(spec.name()));
        }

        var specMap = specFields(spec);
        var specFingerprint = fingerprinter.fingerprint(specMap);

        var existing = findByName(organizationId, spec.name());
        if (existing.isEmpty()) {
            var created = notificationChannelService.create(new CreateNotificationChannelCommand(
                    organizationId, spec.channelType(), spec.name(), spec.config()));
            log.info("Bootstrap: created notification channel '{}' (id={})", spec.name(), created.id());
            stateTracker.recordFingerprintAndPublish(organizationId,
                    BootstrapResourceType.NOTIFICATION_CHANNEL,
                    created.id(), specFingerprint,
                    new BootstrapResourceUpsertedEvent(
                            organizationId,
                            BootstrapResourceType.NOTIFICATION_CHANNEL,
                            created.id(),
                            BootstrapChangeKind.CREATE,
                            List.of(),
                            Map.of("name", created.name(), "channel_type", created.channelType().name())));
            return created.id();
        }

        var view = existing.get();
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.NOTIFICATION_CHANNEL, view.id())
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: notification channel '{}' unchanged, skipping update", spec.name());
            return view.id();
        }

        var viewMap = viewFields(view);
        var updated = notificationChannelService.update(view.id(), organizationId,
                new UpdateNotificationChannelCommand(spec.name(), spec.config(),
                        spec.active() == null ? Boolean.TRUE : spec.active()));
        log.info("Bootstrap: updated notification channel '{}' (id={})", spec.name(), updated.id());
        stateTracker.recordFingerprintAndPublish(organizationId,
                BootstrapResourceType.NOTIFICATION_CHANNEL,
                updated.id(), specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.NOTIFICATION_CHANNEL,
                        updated.id(),
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(viewMap, specMap),
                        Map.of("name", updated.name(), "channel_type", updated.channelType().name())));
        return updated.id();
    }

    private Optional<NotificationChannelView> findByName(UUID organizationId, String name) {
        return notificationChannelService.list(organizationId).stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private static Map<String, Object> specFields(NotificationChannelSpec spec) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", spec.name());
        map.put("channel_type", spec.channelType().name());
        map.put("active", spec.active() == null ? Boolean.TRUE : spec.active());
        map.put("config", spec.config());
        return map;
    }

    private static Map<String, Object> viewFields(NotificationChannelView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", view.name());
        map.put("channel_type", view.channelType().name());
        map.put("active", view.active());
        map.put("config", view.config());
        return map;
    }
}
