package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Class-level constraint: an access request targets exactly one of datasource / API connector,
 * and only carries the fields that make sense for that kind (no DDL, query pre-approval, or
 * schema/table scope on connector requests; no operation scope on datasource requests).
 */
@Documented
@Constraint(validatedBy = ExactlyOneResourceValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ExactlyOneResource {

    String message() default "{validation.access.resource.exactly_one}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
