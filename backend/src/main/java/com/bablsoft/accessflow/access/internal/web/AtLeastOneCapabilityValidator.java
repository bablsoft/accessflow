package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AtLeastOneCapabilityValidator
        implements ConstraintValidator<AtLeastOneCapability, SubmitAccessRequestBody> {

    @Override
    public boolean isValid(SubmitAccessRequestBody body, ConstraintValidatorContext context) {
        if (body == null) {
            return true;
        }
        return Boolean.TRUE.equals(body.canRead())
                || Boolean.TRUE.equals(body.canWrite())
                || Boolean.TRUE.equals(body.canDdl());
    }
}
