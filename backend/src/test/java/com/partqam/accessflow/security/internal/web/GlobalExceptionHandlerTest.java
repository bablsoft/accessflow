package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.core.api.DatasourceConnectionTestException;
import com.partqam.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourceNotFoundException;
import com.partqam.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.DriverResolutionException;
import com.partqam.accessflow.core.api.EmailAlreadyExistsException;
import com.partqam.accessflow.core.api.IllegalDatasourcePermissionException;
import com.partqam.accessflow.core.api.IllegalUserOperationException;
import com.partqam.accessflow.core.api.ReviewPlanNameAlreadyExistsException;
import com.partqam.accessflow.core.api.UserNotFoundException;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.InvalidSqlException;
import com.partqam.accessflow.proxy.api.PoolInitializationException;
import com.partqam.accessflow.proxy.api.QueryExecutionFailedException;
import com.partqam.accessflow.proxy.api.QueryExecutionTimeoutException;
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
        var ex = new QueryExecutionFailedException("relation missing", "42P01", 7,
                new RuntimeException());

        var pd = handler.handleQueryExecutionFailed(ex);

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "QUERY_EXECUTION_FAILED");
        assertThat(pd.getProperties()).containsEntry("sqlState", "42P01");
        assertThat(pd.getProperties()).containsEntry("vendorCode", 7);
    }

    @Test
    void queryExecutionFailedWithoutSqlStateOmitsTheProperty() {
        var ex = new QueryExecutionFailedException("oops", null, 0, new RuntimeException());

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
