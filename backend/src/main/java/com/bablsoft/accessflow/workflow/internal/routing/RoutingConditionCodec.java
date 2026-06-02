package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Single boundary for the polymorphic {@code routing_policy.condition} wire shape. Builds a
 * dedicated {@link ObjectMapper} from the injected one (inheriting the global SNAKE_CASE naming and
 * non-null inclusion) with the {@link ConditionNodeMixin} registered, so the global mapper stays
 * untouched. Used both for the JSONB column (string) and for translating the API request/response
 * condition ({@link JsonNode}) to and from the pure {@link ConditionNode}.
 */
@Component
public class RoutingConditionCodec {

    private final ObjectMapper mapper;
    private final MessageSource messageSource;

    public RoutingConditionCodec(ObjectMapper objectMapper, MessageSource messageSource) {
        this.mapper = objectMapper.rebuild()
                .addMixIn(ConditionNode.class, ConditionNodeMixin.class)
                .build();
        this.messageSource = messageSource;
    }

    /** Serialise a condition tree to the JSON string stored in the JSONB column. */
    public String encode(ConditionNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (RuntimeException ex) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_condition_invalid"), ex);
        }
    }

    /** Deserialise the stored JSONB string back into a condition tree. */
    public ConditionNode decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_condition_required"));
        }
        try {
            return mapper.readValue(json, ConditionNode.class);
        } catch (RuntimeException ex) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_condition_invalid"), ex);
        }
    }

    /** Translate an inbound API condition tree ({@link JsonNode}) into a pure {@link ConditionNode}. */
    public ConditionNode fromJson(JsonNode json) {
        if (json == null || json.isNull() || json.isEmpty()) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_condition_required"));
        }
        try {
            return mapper.treeToValue(json, ConditionNode.class);
        } catch (RuntimeException ex) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_condition_invalid"), ex);
        }
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /** Render a condition tree as a {@link JsonNode} for an API response. */
    public JsonNode toJson(ConditionNode node) {
        return mapper.valueToTree(node);
    }
}
