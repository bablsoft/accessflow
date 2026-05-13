package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.CustomDriverChecksumMismatchException;
import com.bablsoft.accessflow.core.api.CustomDriverDuplicateException;
import com.bablsoft.accessflow.core.api.CustomDriverInUseException;
import com.bablsoft.accessflow.core.api.CustomDriverInvalidJarException;
import com.bablsoft.accessflow.core.api.CustomDriverNotFoundException;
import com.bablsoft.accessflow.core.api.CustomDriverTooLargeException;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.IllegalDatasourcePermissionException;
import com.bablsoft.accessflow.core.api.MissingAiConfigForDatasourceException;
import com.bablsoft.accessflow.core.api.IllegalLocalizationConfigException;
import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.IllegalReviewPlanException;
import com.bablsoft.accessflow.core.api.IllegalUserOperationException;
import com.bablsoft.accessflow.core.api.LanguageNotInAllowedListException;
import com.bablsoft.accessflow.core.api.PasswordChangeNotAllowedException;
import com.bablsoft.accessflow.core.api.PasswordIncorrectException;
import com.bablsoft.accessflow.core.api.TotpAlreadyEnabledException;
import com.bablsoft.accessflow.core.api.TotpInvalidCodeException;
import com.bablsoft.accessflow.core.api.TotpNotEnabledException;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.ReviewPlanInUseException;
import com.bablsoft.accessflow.core.api.ReviewPlanNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ReviewPlanNotFoundException;
import com.bablsoft.accessflow.core.api.SetupAlreadyCompletedException;
import com.bablsoft.accessflow.core.api.SystemSmtpDeliveryException;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.UnsupportedLanguageException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.security.api.DuplicatePendingInvitationException;
import com.bablsoft.accessflow.security.api.InvitationAlreadyAcceptedException;
import com.bablsoft.accessflow.security.api.InvitationExpiredException;
import com.bablsoft.accessflow.security.api.InvitationNotFoundException;
import com.bablsoft.accessflow.security.api.InvitationRevokedException;
import com.bablsoft.accessflow.security.api.OAuth2ConfigInvalidException;
import com.bablsoft.accessflow.security.api.OAuth2ConfigNotFoundException;
import com.bablsoft.accessflow.security.api.SystemSmtpNotConfiguredForInviteException;
import com.bablsoft.accessflow.security.api.TotpAuthenticationException;
import com.bablsoft.accessflow.security.api.TotpRequiredException;
import com.bablsoft.accessflow.proxy.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.PoolInitializationException;
import com.bablsoft.accessflow.proxy.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.proxy.api.QueryExecutionTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
class GlobalExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a));
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg("error.validation_failed"));
        pd.setProperty("error", "VALIDATION_ERROR");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("fields", fieldErrors);
        return pd;
    }

    @ExceptionHandler(TotpRequiredException.class)
    ProblemDetail handleTotpRequired(TotpRequiredException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, msg("error.totp_required"));
        pd.setProperty("error", "TOTP_REQUIRED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(TotpAuthenticationException.class)
    ProblemDetail handleTotpAuthentication(TotpAuthenticationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, msg("error.totp_invalid"));
        pd.setProperty("error", "TOTP_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, msg("error.unauthorized"));
        pd.setProperty("error", "UNAUTHORIZED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(PasswordIncorrectException.class)
    ProblemDetail handlePasswordIncorrect(PasswordIncorrectException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.password_incorrect"));
        pd.setProperty("error", "PASSWORD_INCORRECT");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(PasswordChangeNotAllowedException.class)
    ProblemDetail handlePasswordChangeNotAllowed(PasswordChangeNotAllowedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.password_change_not_allowed"));
        pd.setProperty("error", "PASSWORD_CHANGE_NOT_ALLOWED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(TotpNotEnabledException.class)
    ProblemDetail handleTotpNotEnabled(TotpNotEnabledException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.totp_not_enabled"));
        pd.setProperty("error", "TOTP_NOT_ENABLED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(TotpAlreadyEnabledException.class)
    ProblemDetail handleTotpAlreadyEnabled(TotpAlreadyEnabledException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.totp_already_enabled"));
        pd.setProperty("error", "TOTP_ALREADY_ENABLED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(TotpInvalidCodeException.class)
    ProblemDetail handleTotpInvalidCode(TotpInvalidCodeException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.totp_invalid_code"));
        pd.setProperty("error", "TOTP_INVALID_CODE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, msg("error.forbidden"));
        pd.setProperty("error", "FORBIDDEN");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.email_already_exists"));
        pd.setProperty("error", "EMAIL_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(SetupAlreadyCompletedException.class)
    ProblemDetail handleSetupAlreadyCompleted(SetupAlreadyCompletedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.setup_already_completed"));
        pd.setProperty("error", "SETUP_ALREADY_COMPLETED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.user_not_found"));
        pd.setProperty("error", "USER_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalUserOperationException.class)
    ProblemDetail handleIllegalUserOperation(IllegalUserOperationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.illegal_user_operation"));
        pd.setProperty("error", "ILLEGAL_USER_OPERATION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceNotFoundException.class)
    ProblemDetail handleDatasourceNotFound(DatasourceNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.datasource_not_found"));
        pd.setProperty("error", "DATASOURCE_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceNameAlreadyExistsException.class)
    ProblemDetail handleDatasourceNameAlreadyExists(DatasourceNameAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.datasource_name_already_exists"));
        pd.setProperty("error", "DATASOURCE_NAME_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourcePermissionAlreadyExistsException.class)
    ProblemDetail handleDatasourcePermissionAlreadyExists(
            DatasourcePermissionAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.datasource_permission_already_exists"));
        pd.setProperty("error", "DATASOURCE_PERMISSION_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourcePermissionNotFoundException.class)
    ProblemDetail handleDatasourcePermissionNotFound(DatasourcePermissionNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.datasource_permission_not_found"));
        pd.setProperty("error", "DATASOURCE_PERMISSION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DatasourceConnectionTestException.class)
    ProblemDetail handleDatasourceConnectionTest(DatasourceConnectionTestException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.datasource_connection_test_failed"));
        pd.setProperty("error", "DATASOURCE_CONNECTION_TEST_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DriverResolutionException.class)
    ProblemDetail handleDriverResolution(DriverResolutionException ex) {
        // Message is resolved at throw site via MessageSource — see DefaultDriverCatalogService.
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_DRIVER_UNAVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("dbType", ex.dbType().name());
        pd.setProperty("reason", ex.reason().name());
        return pd;
    }

    @ExceptionHandler(IllegalDatasourcePermissionException.class)
    ProblemDetail handleIllegalDatasourcePermission(IllegalDatasourcePermissionException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.illegal_datasource_permission"));
        pd.setProperty("error", "ILLEGAL_DATASOURCE_PERMISSION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ReviewPlanNotFoundException.class)
    ProblemDetail handleReviewPlanNotFound(ReviewPlanNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.review_plan_not_found"));
        pd.setProperty("error", "REVIEW_PLAN_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ReviewPlanInUseException.class)
    ProblemDetail handleReviewPlanInUse(ReviewPlanInUseException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.review_plan_in_use"));
        pd.setProperty("error", "REVIEW_PLAN_IN_USE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ReviewPlanNameAlreadyExistsException.class)
    ProblemDetail handleReviewPlanNameAlreadyExists(ReviewPlanNameAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.review_plan_name_already_exists"));
        pd.setProperty("error", "REVIEW_PLAN_NAME_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalReviewPlanException.class)
    ProblemDetail handleIllegalReviewPlan(IllegalReviewPlanException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.illegal_review_plan"));
        pd.setProperty("error", "ILLEGAL_REVIEW_PLAN");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(InvalidSqlException.class)
    ProblemDetail handleInvalidSql(InvalidSqlException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "INVALID_SQL");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(QueryExecutionTimeoutException.class)
    ProblemDetail handleQueryExecutionTimeout(QueryExecutionTimeoutException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                msg("error.query_execution_timeout", ex.timeout().toSeconds()));
        pd.setProperty("error", "QUERY_EXECUTION_TIMEOUT");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("timeoutSeconds", ex.timeout().toSeconds());
        return pd;
    }

    @ExceptionHandler(QueryExecutionFailedException.class)
    ProblemDetail handleQueryExecutionFailed(QueryExecutionFailedException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "QUERY_EXECUTION_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        if (ex.sqlState() != null) {
            pd.setProperty("sqlState", ex.sqlState());
        }
        pd.setProperty("vendorCode", ex.vendorCode());
        return pd;
    }

    @ExceptionHandler(DatasourceUnavailableException.class)
    ProblemDetail handleDatasourceUnavailable(DatasourceUnavailableException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "DATASOURCE_UNAVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(PoolInitializationException.class)
    ProblemDetail handlePoolInitialization(PoolInitializationException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setProperty("error", "POOL_INITIALIZATION_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(QueryRequestNotFoundException.class)
    ProblemDetail handleQueryRequestNotFound(QueryRequestNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.query_request_not_found"));
        pd.setProperty("error", "QUERY_REQUEST_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(IllegalQueryStatusTransitionException.class)
    ProblemDetail handleIllegalQueryStatusTransition(IllegalQueryStatusTransitionException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.illegal_status_transition"));
        pd.setProperty("error", "ILLEGAL_STATUS_TRANSITION");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("actual", ex.actual().name());
        pd.setProperty("expected", ex.expected().name());
        return pd;
    }

    @ExceptionHandler(UnsupportedLanguageException.class)
    ProblemDetail handleUnsupportedLanguage(UnsupportedLanguageException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                msg("error.unsupported_language"));
        pd.setProperty("error", "UNSUPPORTED_LANGUAGE");
        pd.setProperty("timestamp", Instant.now().toString());
        if (ex.code() != null) {
            pd.setProperty("language", ex.code());
        }
        return pd;
    }

    @ExceptionHandler(LanguageNotInAllowedListException.class)
    ProblemDetail handleLanguageNotInAllowedList(LanguageNotInAllowedListException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                msg("error.language_not_in_allowed_list"));
        pd.setProperty("error", "LANGUAGE_NOT_IN_ALLOWED_LIST");
        pd.setProperty("timestamp", Instant.now().toString());
        if (ex.code() != null) {
            pd.setProperty("language", ex.code());
        }
        return pd;
    }

    @ExceptionHandler(IllegalLocalizationConfigException.class)
    ProblemDetail handleIllegalLocalizationConfig(IllegalLocalizationConfigException ex) {
        // Message resolved at throw site via MessageSource
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setProperty("error", "ILLEGAL_LOCALIZATION_CONFIG");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(MissingAiConfigForDatasourceException.class)
    ProblemDetail handleMissingAiConfigForDatasource(MissingAiConfigForDatasourceException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.datasource.ai_config_required"));
        pd.setProperty("error", "AI_CONFIG_REQUIRED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(CustomDriverNotFoundException.class)
    ProblemDetail handleCustomDriverNotFound(CustomDriverNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.custom_driver.not_found"));
        pd.setProperty("error", "CUSTOM_DRIVER_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("driverId", ex.driverId().toString());
        return pd;
    }

    @ExceptionHandler(CustomDriverDuplicateException.class)
    ProblemDetail handleCustomDriverDuplicate(CustomDriverDuplicateException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.custom_driver.duplicate"));
        pd.setProperty("error", "CUSTOM_DRIVER_DUPLICATE");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("existingDriverId", ex.existingDriverId().toString());
        return pd;
    }

    @ExceptionHandler(CustomDriverInUseException.class)
    ProblemDetail handleCustomDriverInUse(CustomDriverInUseException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.custom_driver.in_use"));
        pd.setProperty("error", "CUSTOM_DRIVER_IN_USE");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("referencedBy", ex.referencedBy().stream().map(Object::toString).toList());
        return pd;
    }

    @ExceptionHandler(CustomDriverChecksumMismatchException.class)
    ProblemDetail handleCustomDriverChecksumMismatch(CustomDriverChecksumMismatchException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.custom_driver.checksum_mismatch", ex.expectedSha256(), ex.actualSha256()));
        pd.setProperty("error", "CUSTOM_DRIVER_CHECKSUM_MISMATCH");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("expectedSha256", ex.expectedSha256());
        pd.setProperty("actualSha256", ex.actualSha256());
        return pd;
    }

    @ExceptionHandler(CustomDriverTooLargeException.class)
    ProblemDetail handleCustomDriverTooLarge(CustomDriverTooLargeException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                msg("error.custom_driver.too_large", ex.maxBytes()));
        pd.setProperty("error", "CUSTOM_DRIVER_TOO_LARGE");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("maxBytes", ex.maxBytes());
        return pd;
    }

    @ExceptionHandler(CustomDriverInvalidJarException.class)
    ProblemDetail handleCustomDriverInvalidJar(CustomDriverInvalidJarException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.custom_driver.invalid_jar", ex.driverClass()));
        pd.setProperty("error", "CUSTOM_DRIVER_INVALID_JAR");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("driverClass", ex.driverClass());
        return pd;
    }

    @ExceptionHandler(OAuth2ConfigNotFoundException.class)
    ProblemDetail handleOAuth2ConfigNotFound(OAuth2ConfigNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.oauth2.config_not_found"));
        pd.setProperty("error", "OAUTH2_CONFIG_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("provider", ex.provider().name());
        return pd;
    }

    @ExceptionHandler(OAuth2ConfigInvalidException.class)
    ProblemDetail handleOAuth2ConfigInvalid(OAuth2ConfigInvalidException ex) {
        // Message resolved at throw site via MessageSource.
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "OAUTH2_CONFIG_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(SystemSmtpNotConfiguredException.class)
    ProblemDetail handleSystemSmtpNotConfigured(SystemSmtpNotConfiguredException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.system_smtp.not_configured"));
        pd.setProperty("error", "SYSTEM_SMTP_NOT_CONFIGURED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(SystemSmtpDeliveryException.class)
    ProblemDetail handleSystemSmtpDelivery(SystemSmtpDeliveryException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                msg("error.notification_delivery_failed"));
        pd.setProperty("error", "SYSTEM_SMTP_DELIVERY_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(SystemSmtpNotConfiguredForInviteException.class)
    ProblemDetail handleSystemSmtpNotConfiguredForInvite(SystemSmtpNotConfiguredForInviteException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.system_smtp.not_configured_for_invite"));
        pd.setProperty("error", "SYSTEM_SMTP_NOT_CONFIGURED_FOR_INVITE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    ProblemDetail handleInvitationNotFound(InvitationNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.invitation.not_found"));
        pd.setProperty("error", "INVITATION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(InvitationExpiredException.class)
    ProblemDetail handleInvitationExpired(InvitationExpiredException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.invitation.expired"));
        pd.setProperty("error", "INVITATION_EXPIRED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(InvitationAlreadyAcceptedException.class)
    ProblemDetail handleInvitationAlreadyAccepted(InvitationAlreadyAcceptedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.invitation.already_accepted"));
        pd.setProperty("error", "INVITATION_ALREADY_ACCEPTED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(InvitationRevokedException.class)
    ProblemDetail handleInvitationRevoked(InvitationRevokedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.invitation.revoked"));
        pd.setProperty("error", "INVITATION_REVOKED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DuplicatePendingInvitationException.class)
    ProblemDetail handleDuplicatePendingInvitation(DuplicatePendingInvitationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.invitation.duplicate_pending"));
        pd.setProperty("error", "DUPLICATE_PENDING_INVITATION");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setProperty("error", "NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneral(Exception ex) {
        log.error("Unhandled exception bubbled to GlobalExceptionHandler", ex);
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, msg("error.internal"));
        pd.setProperty("error", "INTERNAL_SERVER_ERROR");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
