package com.partqam.accessflow.notifications.api;

import java.util.List;
import java.util.UUID;

public interface NotificationChannelService {

    List<NotificationChannelView> list(UUID organizationId);

    NotificationChannelView create(CreateNotificationChannelCommand command);

    NotificationChannelView update(UUID id, UUID organizationId,
                                   UpdateNotificationChannelCommand command);

    void sendTest(UUID id, UUID organizationId, String optionalEmailOverride);
}
