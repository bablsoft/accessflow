package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.ServiceNowChannelConfig;
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

/**
 * Creates ServiceNow incidents through the Table API
 * ({@code POST {instance_url}/api/now/table/incident}, Basic auth) for the triggers enabled on a
 * {@code SERVICENOW} channel (AF-453). The response's {@code result.sys_id} / {@code result.number}
 * become the persisted ticket link.
 */
@Component
class ServiceNowNotificationStrategy extends TicketingNotificationStrategy {

    static final String INITIAL_STATUS = "New";

    private final ChannelConfigCodec codec;
    private final ObjectMapper objectMapper;

    ServiceNowNotificationStrategy(ChannelConfigCodec codec,
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
        return NotificationChannelType.SERVICENOW;
    }

    @Override
    protected TicketingChannelConfig decodeConfig(String storedJson) {
        return codec.decodeServiceNow(storedJson);
    }

    @Override
    protected TicketRequest buildCreateRequest(NotificationContext ctx,
                                               TicketingChannelConfig config) {
        var sn = (ServiceNowChannelConfig) config;
        var body = new LinkedHashMap<String, Object>();
        body.put("short_description", TicketDescriptionBuilder.summary(ctx));
        body.put("description", TicketDescriptionBuilder.description(ctx));
        if (ctx.queryRequestId() != null) {
            body.put("correlation_id", "accessflow-" + ctx.queryRequestId());
        }
        if (sn.urgency() != null) {
            body.put("urgency", sn.urgency().toString());
        }
        if (sn.assignmentGroup() != null && !sn.assignmentGroup().isBlank()) {
            body.put("assignment_group", sn.assignmentGroup());
        }
        return new TicketRequest(
                resolve(sn.instanceUrl(), "api/now/table/incident"),
                basicAuth(sn.username(), sn.passwordPlain()),
                objectMapper.writeValueAsString(body));
    }

    @Override
    protected TicketRef parseTicketRef(String responseBody, TicketingChannelConfig config) {
        var sn = (ServiceNowChannelConfig) config;
        var root = objectMapper.readTree(responseBody);
        var result = root.path("result");
        var sysId = result.path("sys_id").asString("");
        var number = result.path("number").asString("");
        if (sysId.isBlank()) {
            throw new NotificationDeliveryException(
                    "ServiceNow create-incident response is missing result.sys_id");
        }
        var url = resolve(sn.instanceUrl(), "nav_to.do?uri=incident.do?sys_id=" + sysId);
        return new TicketRef(sysId, number.isBlank() ? sysId : number, url.toString(),
                INITIAL_STATUS);
    }

    @Override
    protected TestRequest buildTestRequest(TicketingChannelConfig config) {
        var sn = (ServiceNowChannelConfig) config;
        return new TestRequest(
                resolve(sn.instanceUrl(), "api/now/table/incident?sysparm_limit=1"),
                basicAuth(sn.username(), sn.passwordPlain()));
    }

    private static URI resolve(URI instanceUrl, String path) {
        var base = instanceUrl.toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return URI.create(base + path);
    }
}
