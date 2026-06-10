package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.IllegalDatasourcePermissionException;
import com.bablsoft.accessflow.core.api.IllegalUserOperationException;
import com.bablsoft.accessflow.core.api.ReviewPlanNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupMembershipNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceReviewerAlreadyExistsException;
import com.bablsoft.accessflow.core.api.IllegalDatasourceReviewerException;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.PoolInitializationException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock MessageSource messageSource;

    private GlobalExceptionHandler handler;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        handler = new GlobalExceptionHandler(messageSource);
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void validationErrorReturns400WithFieldErrors() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "email", "must not be blank"));
        var ex = new MethodArgumentNotValidException(stubMethodParameter(), binding);

        var pd = handler.handleValidation(ex);

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("error", "VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        var fields = (Map<String, String>) pd.getProperties().get("fields");
        assertThat(fields).containsEntry("email", "must not be blank");
    }

    @Test
    void validationErrorWithDuplicateFieldKeepsFirstMessage() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "email", "first message"));
        binding.addError(new FieldError("request", "email", "second message"));
        var ex = new MethodArgumentNotValidException(stubMethodParameter(), binding);

        var pd = handler.handleValidation(ex);

        @SuppressWarnings("unchecked")
        var fields = (Map<String, String>) pd.getProperties().get("fields");
        assertThat(fields).containsEntry("email", "first message");
    }

    @Test
    void authenticationExceptionReturns401() {
        var pd = handler.handleAuthentication(new AuthenticationException("bad creds") {
        });

        assertThat(pd.getStatus()).isEqualTo(401);
        assertThat(pd.getProperties()).containsEntry("error", "UNAUTHORIZED");
    }

    @Test
    void accessDeniedReturns403() {
        var pd = handler.handleAccessDenied(new AccessDeniedException("forbidden"));

        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getProperties()).containsEntry("error", "FORBIDDEN");
    }

    @Test
    void emailAlreadyExistsReturns409() {
        var pd = handler.handleEmailAlreadyExists(new EmailAlreadyExistsException("x@y.z"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error", "EMAIL_ALREADY_EXISTS");
    }

    @Test
    void userNotFoundReturns404() {
        var pd = handler.handleUserNotFound(new UserNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "USER_NOT_FOUND");
    }

    @Test
    void illegalUserOperationReturns422() {
        var pd = handler.handleIllegalUserOperation(new IllegalUserOperationException("nope"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "ILLEGAL_USER_OPERATION");
    }

    @Test
    void datasourceNotFoundReturns404() {
        var pd = handler.handleDatasourceNotFound(new DatasourceNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "DATASOURCE_NOT_FOUND");
    }

    @Test
    void datasourceNameAlreadyExistsReturns409() {
        var pd = handler.handleDatasourceNameAlreadyExists(
                new DatasourceNameAlreadyExistsException("dup"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties())
                .containsEntry("error", "DATASOURCE_NAME_ALREADY_EXISTS");
    }

    @Test
    void datasourcePermissionAlreadyExistsReturns409() {
        var pd = handler.handleDatasourcePermissionAlreadyExists(
                new DatasourcePermissionAlreadyExistsException(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties())
                .containsEntry("error", "DATASOURCE_PERMISSION_ALREADY_EXISTS");
    }

    @Test
    void reviewPlanNameAlreadyExistsReturns409() {
        var pd = handler.handleReviewPlanNameAlreadyExists(
                new ReviewPlanNameAlreadyExistsException("Default"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties())
                .containsEntry("error", "REVIEW_PLAN_NAME_ALREADY_EXISTS");
    }

    @Test
    void datasourcePermissionNotFoundReturns404() {
        var pd = handler.handleDatasourcePermissionNotFound(
                new DatasourcePermissionNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties())
                .containsEntry("error", "DATASOURCE_PERMISSION_NOT_FOUND");
    }

    @Test
    void datasourceConnectionTestReturns422() {
        var pd = handler.handleDatasourceConnectionTest(
                new DatasourceConnectionTestException("can't connect"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties())
                .containsEntry("error", "DATASOURCE_CONNECTION_TEST_FAILED");
    }

    @Test
    void driverResolutionReturns422WithReasonAndDbType() {
        var ex = new DriverResolutionException(DbType.MYSQL,
                DriverResolutionException.Reason.OFFLINE_CACHE_MISS,
                "missing in cache");

        var pd = handler.handleDriverResolution(ex);

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getDetail()).isEqualTo("missing in cache");
        assertThat(pd.getProperties())
                .containsEntry("error", "DATASOURCE_DRIVER_UNAVAILABLE")
                .containsEntry("dbType", "MYSQL")
                .containsEntry("reason", "OFFLINE_CACHE_MISS");
    }

    @Test
    void illegalDatasourcePermissionReturns422() {
        var pd = handler.handleIllegalDatasourcePermission(
                new IllegalDatasourcePermissionException("bad"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties())
                .containsEntry("error", "ILLEGAL_DATASOURCE_PERMISSION");
    }

    @Test
    void invalidSqlReturns422() {
        var pd = handler.handleInvalidSql(new InvalidSqlException("nope"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "INVALID_SQL");
    }

    @Test
    void queryExecutionTimeoutReturns504WithTimeoutMetadata() {
        var ex = new QueryExecutionTimeoutException(
                "timed out", Duration.ofSeconds(7), new RuntimeException());

        var pd = handler.handleQueryExecutionTimeout(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_EXECUTION_TIMEOUT");
        assertThat(pd.getProperties()).containsEntry("timeoutSeconds", 7L);
    }

    @Test
    void queryExecutionFailedReturns422WithSqlStateAndVendorCode() {
        var ex = new QueryExecutionFailedException("relation missing",
                "ERROR: relation \"x\" does not exist", "42P01", 7,
                new RuntimeException());

        var pd = handler.handleQueryExecutionFailed(ex);

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_EXECUTION_FAILED");
        assertThat(pd.getProperties()).containsEntry("sqlState", "42P01");
        assertThat(pd.getProperties()).containsEntry("vendorCode", 7);
    }

    @Test
    void queryExecutionFailedWithoutSqlStateOmitsTheProperty() {
        var ex = new QueryExecutionFailedException("oops", "oops detail", null, 0,
                new RuntimeException());

        var pd = handler.handleQueryExecutionFailed(ex);

        assertThat(pd.getProperties()).doesNotContainKey("sqlState");
        assertThat(pd.getProperties()).containsEntry("vendorCode", 0);
    }

    @Test
    void datasourceUnavailableReturns422() {
        var pd = handler.handleDatasourceUnavailable(
                new DatasourceUnavailableException("inactive"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "DATASOURCE_UNAVAILABLE");
    }

    @Test
    void poolInitializationFailedReturns503() {
        var pd = handler.handlePoolInitialization(
                new PoolInitializationException("bad creds", new RuntimeException()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getProperties()).containsEntry("error", "POOL_INITIALIZATION_FAILED");
    }

    @Test
    void uncaughtExceptionReturns500() {
        var pd = handler.handleGeneral(new RuntimeException("boom"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getProperties()).containsEntry("error", "INTERNAL_SERVER_ERROR");
    }

    @Test
    void uncaughtExceptionLogsStackTraceAtErrorLevel() {
        var cause = new RuntimeException("boom");

        handler.handleGeneral(cause);

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getClassName())
                            .isEqualTo(RuntimeException.class.getName());
                    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
                });
    }

    @Test
    void userGroupNotFoundReturns404() {
        var pd = handler.handleUserGroupNotFound(new UserGroupNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "USER_GROUP_NOT_FOUND");
    }

    @Test
    void userGroupNameAlreadyExistsReturns409() {
        var pd = handler.handleUserGroupNameAlreadyExists(
                new UserGroupNameAlreadyExistsException("Eng"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error", "USER_GROUP_NAME_ALREADY_EXISTS");
    }

    @Test
    void userGroupMembershipNotFoundReturns404() {
        var pd = handler.handleUserGroupMembershipNotFound(
                new UserGroupMembershipNotFoundException(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "USER_GROUP_MEMBERSHIP_NOT_FOUND");
    }

    @Test
    void datasourceReviewerNotFoundReturns404() {
        var pd = handler.handleDatasourceReviewerNotFound(
                new DatasourceReviewerNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "DATASOURCE_REVIEWER_NOT_FOUND");
    }

    @Test
    void datasourceReviewerAlreadyExistsReturns409() {
        var pd = handler.handleDatasourceReviewerAlreadyExists(
                new DatasourceReviewerAlreadyExistsException("dup"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error",
                "DATASOURCE_REVIEWER_ALREADY_EXISTS");
    }

    @Test
    void illegalDatasourceReviewerReturns422() {
        var pd = handler.handleIllegalDatasourceReviewer(
                new IllegalDatasourceReviewerException("xor"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "ILLEGAL_DATASOURCE_REVIEWER");
    }

    @Test
    void unrewritableRowSecurityReturns422() {
        var pd = handler.handleUnrewritableRowSecurity(
                new com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException("cte"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "ROW_SECURITY_UNREWRITABLE");
        assertThat(pd.getDetail()).isEqualTo("cte");
    }

    @Test
    void illegalQueryStatusTransitionReturns409WithActualAndExpected() {
        var pd = handler.handleIllegalQueryStatusTransition(
                new com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException(
                        UUID.randomUUID(),
                        com.bablsoft.accessflow.core.api.QueryStatus.PENDING_AI,
                        com.bablsoft.accessflow.core.api.QueryStatus.APPROVED));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error", "ILLEGAL_STATUS_TRANSITION")
                .containsEntry("actual", "PENDING_AI")
                .containsEntry("expected", "APPROVED");
    }

    @Test
    void unsupportedLanguageReturns400WithLanguageProperty() {
        var pd = handler.handleUnsupportedLanguage(
                new com.bablsoft.accessflow.core.api.UnsupportedLanguageException("xx"));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("error", "UNSUPPORTED_LANGUAGE")
                .containsEntry("language", "xx");
    }

    @Test
    void unsupportedLanguageWithNullCodeOmitsLanguageProperty() {
        var pd = handler.handleUnsupportedLanguage(
                new com.bablsoft.accessflow.core.api.UnsupportedLanguageException(null));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).doesNotContainKey("language");
    }

    @Test
    void languageNotInAllowedListReturns400WithLanguageProperty() {
        var pd = handler.handleLanguageNotInAllowedList(
                new com.bablsoft.accessflow.core.api.LanguageNotInAllowedListException("fr"));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("error", "LANGUAGE_NOT_IN_ALLOWED_LIST")
                .containsEntry("language", "fr");
    }

    @Test
    void languageNotInAllowedListWithNullCodeOmitsLanguageProperty() {
        var pd = handler.handleLanguageNotInAllowedList(
                new com.bablsoft.accessflow.core.api.LanguageNotInAllowedListException(null));

        assertThat(pd.getProperties()).doesNotContainKey("language");
    }

    @Test
    void illegalLocalizationConfigReturns400() {
        var pd = handler.handleIllegalLocalizationConfig(
                new com.bablsoft.accessflow.core.api.IllegalLocalizationConfigException("empty"));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("error", "ILLEGAL_LOCALIZATION_CONFIG");
        assertThat(pd.getDetail()).isEqualTo("empty");
    }

    @Test
    void customDriverTooLargeReturns413WithMaxBytes() {
        var pd = handler.handleCustomDriverTooLarge(
                new com.bablsoft.accessflow.core.api.CustomDriverTooLargeException(100, 50));

        assertThat(pd.getStatus()).isEqualTo(413);
        assertThat(pd.getProperties()).containsEntry("error", "CUSTOM_DRIVER_TOO_LARGE")
                .containsEntry("maxBytes", 50L);
    }

    @Test
    void customDriverInvalidJarReturns422WithDriverClass() {
        var pd = handler.handleCustomDriverInvalidJar(
                new com.bablsoft.accessflow.core.api.CustomDriverInvalidJarException(
                        "com.acme.Driver", "not a driver"));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "CUSTOM_DRIVER_INVALID_JAR")
                .containsEntry("driverClass", "com.acme.Driver");
    }

    @Test
    void systemSmtpNotConfiguredReturns422() {
        var pd = handler.handleSystemSmtpNotConfigured(
                new com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "SYSTEM_SMTP_NOT_CONFIGURED");
    }

    @Test
    void systemSmtpDeliveryReturns502() {
        var pd = handler.handleSystemSmtpDelivery(
                new com.bablsoft.accessflow.core.api.SystemSmtpDeliveryException(
                        "smtp down", new RuntimeException("io")));

        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("error", "SYSTEM_SMTP_DELIVERY_FAILED");
    }

    @Test
    void systemSmtpNotConfiguredForInviteReturns422() {
        var pd = handler.handleSystemSmtpNotConfiguredForInvite(
                new com.bablsoft.accessflow.security.api.SystemSmtpNotConfiguredForInviteException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties())
                .containsEntry("error", "SYSTEM_SMTP_NOT_CONFIGURED_FOR_INVITE");
    }

    @Test
    void invitationNotFoundReturns404() {
        var pd = handler.handleInvitationNotFound(
                new com.bablsoft.accessflow.security.api.InvitationNotFoundException());

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "INVITATION_NOT_FOUND");
    }

    @Test
    void invitationExpiredReturns422() {
        var pd = handler.handleInvitationExpired(
                new com.bablsoft.accessflow.security.api.InvitationExpiredException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "INVITATION_EXPIRED");
    }

    @Test
    void invitationAlreadyAcceptedReturns422() {
        var pd = handler.handleInvitationAlreadyAccepted(
                new com.bablsoft.accessflow.security.api.InvitationAlreadyAcceptedException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "INVITATION_ALREADY_ACCEPTED");
    }

    @Test
    void invitationRevokedReturns422() {
        var pd = handler.handleInvitationRevoked(
                new com.bablsoft.accessflow.security.api.InvitationRevokedException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "INVITATION_REVOKED");
    }

    @Test
    void duplicatePendingInvitationReturns409() {
        var pd = handler.handleDuplicatePendingInvitation(
                new com.bablsoft.accessflow.security.api.DuplicatePendingInvitationException());

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("error", "DUPLICATE_PENDING_INVITATION");
    }

    @Test
    void passwordResetNotFoundReturns404() {
        var pd = handler.handlePasswordResetNotFound(
                new com.bablsoft.accessflow.security.api.PasswordResetTokenNotFoundException());

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "PASSWORD_RESET_NOT_FOUND");
    }

    @Test
    void passwordResetExpiredReturns422() {
        var pd = handler.handlePasswordResetExpired(
                new com.bablsoft.accessflow.security.api.PasswordResetTokenExpiredException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "PASSWORD_RESET_EXPIRED");
    }

    @Test
    void passwordResetAlreadyUsedReturns422() {
        var pd = handler.handlePasswordResetAlreadyUsed(
                new com.bablsoft.accessflow.security.api.PasswordResetTokenAlreadyUsedException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "PASSWORD_RESET_ALREADY_USED");
    }

    @Test
    void passwordResetRevokedReturns422() {
        var pd = handler.handlePasswordResetRevoked(
                new com.bablsoft.accessflow.security.api.PasswordResetTokenRevokedException());

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "PASSWORD_RESET_REVOKED");
    }

    @Test
    void noResourceFoundReturns404() {
        var pd = handler.handleNoResourceFound(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/nope", "/nope"));

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "NOT_FOUND");
    }

    private static org.springframework.core.MethodParameter stubMethodParameter() throws Exception {
        Method method = StubController.class.getDeclaredMethod("stub", String.class);
        return new org.springframework.core.MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private static final class StubController {
        void stub(String body) {
        }
    }
}
