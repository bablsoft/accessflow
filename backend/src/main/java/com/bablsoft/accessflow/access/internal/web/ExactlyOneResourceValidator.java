package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

public class ExactlyOneResourceValidator
        implements ConstraintValidator<ExactlyOneResource, SubmitAccessRequestBody> {

    @Override
    public boolean isValid(SubmitAccessRequestBody body, ConstraintValidatorContext context) {
        if (body == null) {
            return true;
        }
        if ((body.datasourceId() == null) == (body.connectorId() == null)) {
            return false;
        }
        if (body.connectorId() != null) {
            return validConnectorShape(body, context);
        }
        return validDatasourceShape(body, context);
    }

    private static boolean validConnectorShape(SubmitAccessRequestBody body,
                                               ConstraintValidatorContext context) {
        if (Boolean.TRUE.equals(body.canDdl())) {
            return violation(context, "{validation.access.connector.no_ddl}");
        }
        if (Boolean.TRUE.equals(body.preApproveQueries())) {
            return violation(context, "{validation.access.connector.no_pre_approve}");
        }
        if (!isEmpty(body.allowedSchemas()) || !isEmpty(body.allowedTables())) {
            return violation(context, "{validation.access.connector.no_schema_scope}");
        }
        return true;
    }

    private static boolean validDatasourceShape(SubmitAccessRequestBody body,
                                                ConstraintValidatorContext context) {
        if (!isEmpty(body.allowedOperations())) {
            return violation(context, "{validation.access.datasource.no_operations}");
        }
        return true;
    }

    private static boolean violation(ConstraintValidatorContext context, String messageTemplate) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(messageTemplate).addConstraintViolation();
        return false;
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }
}
