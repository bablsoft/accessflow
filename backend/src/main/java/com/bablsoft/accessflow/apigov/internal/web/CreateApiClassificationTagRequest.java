package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorClassificationTagCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateApiClassificationTagRequest(
        @NotNull(message = "{validation.api_classification_tag.matcher.required}")
        ApiMaskingMatcherType matcherType,
        String operationId,
        @NotBlank(message = "{validation.api_classification_tag.field.required}")
        @Size(max = 2048, message = "{validation.api_classification_tag.field.required}")
        String fieldRef,
        @NotEmpty(message = "{validation.api_classification_tag.classifications.required}")
        List<DataClassification> classifications,
        String note,
        Boolean applyMasking) {

    public CreateApiConnectorClassificationTagCommand toCommand() {
        return new CreateApiConnectorClassificationTagCommand(matcherType, operationId, fieldRef,
                classifications, note, applyMasking);
    }
}
