package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserSlackMappingRepository;
import com.bablsoft.accessflow.workflow.api.QueryNotPendingReviewException;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewerNotEligibleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes the approve/reject decision triggered by a verified Slack interactive-component
 * callback. The Slack user is resolved to an AccessFlow user via {@code user_slack_mapping}; the
 * decision goes through the same {@link ReviewService} path (and therefore the same self-approval
 * and RBAC guards) as the REST API. The originating Slack message is mutated in place via the
 * {@code response_url}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackInteractionService {

    public static final String ACTION_APPROVE = "approve";
    public static final String ACTION_REJECT = "reject";

    private final UserSlackMappingRepository mappingRepository;
    private final UserQueryService userQueryService;
    private final ReviewService reviewService;
    private final SlackResponseSender responseSender;
    private final SlackMessages messages;

    public void handleAction(DecryptedSlackApp app, String slackUserId, String actionId,
                             String value, String responseUrl) {
        var organizationId = app.organizationId();
        var user = resolveUser(organizationId, slackUserId);
        if (user == null) {
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.not_linked"));
            return;
        }
        var queryRequestId = parseUuid(value);
        if (queryRequestId == null || (!ACTION_APPROVE.equals(actionId) && !ACTION_REJECT.equals(actionId))) {
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.invalid"));
            return;
        }
        var context = new ReviewService.ReviewerContext(user.id(), user.organizationId(), user.role());
        try {
            if (ACTION_APPROVE.equals(actionId)) {
                reviewService.approve(queryRequestId, context,
                        messages.forOrg(organizationId, "slack.action.approve_comment"));
                replaceMessage(responseUrl,
                        messages.forOrg(organizationId, "slack.action.approved", user.displayName()));
            } else {
                reviewService.reject(queryRequestId, context,
                        messages.forOrg(organizationId, "slack.action.reject_comment"));
                replaceMessage(responseUrl,
                        messages.forOrg(organizationId, "slack.action.rejected", user.displayName()));
            }
        } catch (AccessDeniedException ex) {
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.self_approval"));
        } catch (ReviewerNotEligibleException ex) {
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.not_eligible"));
        } catch (QueryNotPendingReviewException ex) {
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.not_pending"));
        } catch (QueryRequestNotFoundException ex) {
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.not_found"));
        } catch (RuntimeException ex) {
            log.error("Slack action {} failed for query {}", actionId, queryRequestId, ex);
            ephemeral(responseUrl, messages.forOrg(organizationId, "slack.action.error"));
        }
    }

    private UserView resolveUser(UUID organizationId, String slackUserId) {
        return mappingRepository.findByOrganizationIdAndSlackUserId(organizationId, slackUserId)
                .flatMap(m -> userQueryService.findById(m.getUserId()))
                .filter(UserView::active)
                .orElse(null);
    }

    private void replaceMessage(String responseUrl, String text) {
        var section = Map.<String, Object>of(
                "type", "section",
                "text", Map.of("type", "mrkdwn", "text", text));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("replace_original", true);
        payload.put("text", text);
        payload.put("blocks", List.of(section));
        responseSender.send(responseUrl, payload);
    }

    private void ephemeral(String responseUrl, String text) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("response_type", "ephemeral");
        payload.put("replace_original", false);
        payload.put("text", text);
        responseSender.send(responseUrl, payload);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
