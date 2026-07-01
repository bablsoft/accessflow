package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.requestgroups.api.UpdateRequestGroupCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

record UpdateRequestGroupRequest(
        @NotBlank(message = "{validation.request_group.name.required}")
        @Size(min = 1, max = 255, message = "{validation.request_group.name.size}")
        String name,
        @Size(max = 4000, message = "{validation.request_group.description.size}")
        String description,
        Boolean continueOnError,
        @NotEmpty(message = "{validation.request_group.items.required}")
        @Valid
        List<RequestGroupItemRequest> items) {

    UpdateRequestGroupCommand toCommand(UUID requestGroupId, UUID organizationId, UUID callerUserId,
                                        boolean admin) {
        var order = new AtomicInteger();
        var inputs = items.stream().map(i -> i.toInput(order.getAndIncrement())).toList();
        return new UpdateRequestGroupCommand(requestGroupId, organizationId, callerUserId, admin, name,
                description, continueOnError != null && continueOnError, inputs);
    }
}
