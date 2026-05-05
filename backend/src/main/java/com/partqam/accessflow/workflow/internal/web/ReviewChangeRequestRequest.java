package com.partqam.accessflow.workflow.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewChangeRequestRequest(@NotBlank @Size(max = 4000) String comment) {
}
