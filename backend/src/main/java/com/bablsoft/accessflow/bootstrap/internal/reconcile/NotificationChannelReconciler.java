package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.NotificationChannelSpec;
import com.bablsoft.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.api.NotificationChannelService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelView;
import com.bablsoft.accessflow.notifications.api.UpdateNotificationChannelCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationChannelReconciler {

    private final NotificationChannelService notificationChannelService;

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
        var existing = findByName(organizationId, spec.name());
        if (existing.isPresent()) {
            var view = existing.get();
            var updated = notificationChannelService.update(view.id(), organizationId,
                    new UpdateNotificationChannelCommand(spec.name(), spec.config(),
                            spec.active() == null ? Boolean.TRUE : spec.active()));
            log.info("Bootstrap: updated notification channel '{}' (id={})", spec.name(), updated.id());
            return updated.id();
        }
        var created = notificationChannelService.create(new CreateNotificationChannelCommand(
                organizationId, spec.channelType(), spec.name(), spec.config()));
        log.info("Bootstrap: created notification channel '{}' (id={})", spec.name(), created.id());
        return created.id();
    }

    private Optional<NotificationChannelView> findByName(UUID organizationId, String name) {
        return notificationChannelService.list(organizationId).stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }
}
