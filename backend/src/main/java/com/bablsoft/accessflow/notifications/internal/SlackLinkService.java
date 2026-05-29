package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserSlackMappingEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserSlackMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the AccessFlow ↔ Slack user mapping: issues one-time link codes, consumes them from the
 * {@code /accessflow link <code>} slash command, and supports self-service status/unlink.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackLinkService {

    private final SlackLinkCodeStore linkCodeStore;
    private final UserSlackMappingRepository mappingRepository;
    private final UserQueryService userQueryService;
    private final SlackMessages messages;

    public SlackLinkCodeStore.Issued issueCode(UUID userId) {
        return linkCodeStore.issue(userId);
    }

    @Transactional(readOnly = true)
    public Optional<String> linkStatus(UUID userId) {
        return mappingRepository.findByUserId(userId).map(UserSlackMappingEntity::getSlackUserId);
    }

    @Transactional
    public void unlink(UUID userId) {
        mappingRepository.deleteByUserId(userId);
    }

    /**
     * Handle a verified {@code /accessflow link <code>} slash command. Returns the localized
     * ephemeral text to show the Slack user.
     */
    @Transactional
    public String completeLink(UUID appOrganizationId, String slackUserId, String commandText) {
        var code = parseLinkCode(commandText);
        if (code == null) {
            return messages.forOrg(appOrganizationId, "slack.link.usage");
        }
        var userId = linkCodeStore.consume(code).orElse(null);
        if (userId == null) {
            return messages.forOrg(appOrganizationId, "slack.link.invalid_code");
        }
        var user = userQueryService.findById(userId).orElse(null);
        if (user == null || !appOrganizationId.equals(user.organizationId())) {
            return messages.forOrg(appOrganizationId, "slack.link.invalid_code");
        }
        persistMapping(appOrganizationId, userId, slackUserId);
        return messages.forOrg(appOrganizationId, "slack.link.success", user.email());
    }

    private void persistMapping(UUID organizationId, UUID userId, String slackUserId) {
        mappingRepository.findByOrganizationIdAndSlackUserId(organizationId, slackUserId)
                .filter(m -> !m.getUserId().equals(userId))
                .ifPresent(mappingRepository::delete);
        mappingRepository.flush();
        var entity = mappingRepository.findByUserId(userId).orElseGet(() -> {
            var fresh = new UserSlackMappingEntity();
            fresh.setId(UUID.randomUUID());
            fresh.setOrganizationId(organizationId);
            fresh.setUserId(userId);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });
        entity.setSlackUserId(slackUserId);
        mappingRepository.save(entity);
    }

    private static String parseLinkCode(String commandText) {
        if (commandText == null) {
            return null;
        }
        var parts = commandText.trim().split("\\s+");
        if (parts.length == 2 && parts[0].equalsIgnoreCase("link") && !parts[1].isBlank()) {
            return parts[1];
        }
        return null;
    }
}
