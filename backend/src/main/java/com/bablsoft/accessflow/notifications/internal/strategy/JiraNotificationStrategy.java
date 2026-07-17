package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.JiraChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingChannelConfig;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates Jira issues through the REST v2 API ({@code POST {base_url}/rest/api/2/issue}, Basic
 * auth {@code email:api_token}) for the triggers enabled on a {@code JIRA} channel (AF-453). v2 is
 * deliberate: its plain-text {@code description} avoids the Atlassian Document Format that v3
 * requires. The response's {@code id} / {@code key} become the persisted ticket link.
 */
@Component
class JiraNotificationStrategy extends TicketingNotificationStrategy {

    static final String INITIAL_STATUS = "To Do";

    private final ChannelConfigCodec codec;
    private final ObjectMapper objectMapper;

    JiraNotificationStrategy(ChannelConfigCodec codec,
                             ObjectMapper objectMapper,
                             RestClient restClient,
                             @Qualifier("notificationsTaskScheduler") TaskScheduler taskScheduler,
                             NotificationsProperties properties,
                             NotificationChannelRepository channelRepository,
                             ApplicationEventPublisher eventPublisher,
                             QueryTicketService queryTicketService,
                             AuditLogService auditLogService) {
        super(restClient, taskScheduler, properties, channelRepository, eventPublisher,
                queryTicketService, auditLogService);
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.JIRA;
    }

    @Override
    protected TicketingChannelConfig decodeConfig(String storedJson) {
        return codec.decodeJira(storedJson);
    }

    @Override
    protected TicketRequest buildCreateRequest(NotificationContext ctx,
                                               TicketingChannelConfig config) {
        var jira = (JiraChannelConfig) config;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("project", Map.of("key", jira.projectKey()));
        fields.put("issuetype", Map.of("name", jira.issueType()));
        fields.put("summary", TicketDescriptionBuilder.summary(ctx));
        fields.put("description", TicketDescriptionBuilder.description(ctx));
        fields.put("labels", List.of("accessflow"));
        return new TicketRequest(
                resolve(jira.baseUrl(), "rest/api/2/issue"),
                basicAuth(jira.userEmail(), jira.apiTokenPlain()),
                objectMapper.writeValueAsString(Map.of("fields", fields)));
    }

    @Override
    protected TicketRef parseTicketRef(String responseBody, TicketingChannelConfig config) {
        var jira = (JiraChannelConfig) config;
        var root = objectMapper.readTree(responseBody);
        var id = root.path("id").asString("");
        var key = root.path("key").asString("");
        if (id.isBlank() && key.isBlank()) {
            throw new NotificationDeliveryException(
                    "Jira create-issue response is missing both id and key");
        }
        var externalId = id.isBlank() ? key : id;
        var externalKey = key.isBlank() ? id : key;
        var url = resolve(jira.baseUrl(), "browse/" + externalKey);
        return new TicketRef(externalId, externalKey, url.toString(), INITIAL_STATUS);
    }

    @Override
    protected TestRequest buildTestRequest(TicketingChannelConfig config) {
        var jira = (JiraChannelConfig) config;
        return new TestRequest(
                resolve(jira.baseUrl(), "rest/api/2/myself"),
                basicAuth(jira.userEmail(), jira.apiTokenPlain()));
    }

    private static URI resolve(URI baseUrl, String path) {
        var base = baseUrl.toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return URI.create(base + path);
    }
}
