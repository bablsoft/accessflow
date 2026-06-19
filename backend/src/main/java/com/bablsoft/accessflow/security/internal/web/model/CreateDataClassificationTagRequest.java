package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateDataClassificationTagRequest(
        @NotBlank(message = "{validation.classification_table.required}")
        @Size(max = 256, message = "{validation.classification_table.size}")
        String tableName,
        @Size(max = 256, message = "{validation.classification_column.size}")
        String columnName,
        @NotEmpty(message = "{validation.classifications.required}")
        List<DataClassification> classifications,
        @Size(max = 1000, message = "{validation.classification_note.size}")
        String note,
        Boolean applyMasking
) {}
