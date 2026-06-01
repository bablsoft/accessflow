package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Class-level constraint: an access request must ask for at least one of read / write / DDL. */
@Documented
@Constraint(validatedBy = AtLeastOneCapabilityValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface AtLeastOneCapability {

    String message() default "{validation.access.capability.required}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
