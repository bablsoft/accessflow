package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationChannelNotFoundException;
import com.bablsoft.accessflow.notifications.api.NotificationChannelService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationChannelView;
import com.bablsoft.accessflow.notifications.api.UpdateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.bablsoft.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
class DefaultNotificationChannelService implements NotificationChannelService {

    private final NotificationChannelRepository channelRepository;
    private final ChannelConfigCodec codec;
    private final Map<NotificationChannelType, NotificationChannelStrategy> strategies;

    DefaultNotificationChannelService(NotificationChannelRepository channelRepository,
                                      ChannelConfigCodec codec,
                                      List<NotificationChannelStrategy> strategyBeans) {
        this.channelRepository = channelRepository;
        this.codec = codec;
        var map = new EnumMap<NotificationChannelType, NotificationChannelStrategy>(
                NotificationChannelType.class);
        for (NotificationChannelStrategy strategy : strategyBeans) {
            map.put(strategy.supports(), strategy);
        }
        this.strategies = map;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationChannelView> list(UUID organizationId) {
        return channelRepository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public NotificationChannelView create(CreateNotificationChannelCommand command) {
        if (command.organizationId() == null) {
            throw new NotificationChannelConfigException("organizationId is required");
        }
        if (command.channelType() == null) {
            throw new NotificationChannelConfigException("channelType is required");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new NotificationChannelConfigException("name is required");
        }
        var entity = new NotificationChannelEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setChannelType(command.channelType());
        entity.setName(command.name().trim());
        entity.setActive(true);
        entity.setConfigJson(codec.encodeForPersistence(command.channelType(), command.config()));
        var saved = channelRepository.save(entity);
        return toView(saved);
    }

    @Override
    @Transactional
    public NotificationChannelView update(UUID id, UUID organizationId,
                                          UpdateNotificationChannelCommand command) {
        var entity = channelRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new NotificationChannelNotFoundException(id));
        if (command.name() != null && !command.name().isBlank()) {
            entity.setName(command.name().trim());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        if (command.config() != null && !command.config().isEmpty()) {
            entity.setConfigJson(codec.mergeForPersistence(
                    entity.getChannelType(), entity.getConfigJson(), command.config()));
        }
        var saved = channelRepository.save(entity);
        return toView(saved);
    }

    @Override
    public void sendTest(UUID id, UUID organizationId, String optionalEmailOverride) {
        var entity = channelRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new NotificationChannelNotFoundException(id));
        var strategy = strategies.get(entity.getChannelType());
        if (strategy == null) {
            throw new NotificationChannelConfigException(
                    "No strategy registered for channel type " + entity.getChannelType());
        }
        strategy.sendTest(entity, optionalEmailOverride);
    }

    private NotificationChannelView toView(NotificationChannelEntity entity) {
        return new NotificationChannelView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getChannelType(),
                entity.getName(),
                codec.decodeForApi(entity.getConfigJson()),
                entity.isActive(),
                entity.getCreatedAt());
    }
}
