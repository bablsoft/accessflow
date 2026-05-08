package com.partqam.accessflow.notifications.internal.web;

import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelNotFoundException;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import com.partqam.accessflow.notifications.api.UserNotificationNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice(basePackageClasses = NotificationsExceptionHandler.class)
@RequiredArgsConstructor
class NotificationsExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(NotificationChannelNotFoundException.class)
    ProblemDetail handleNotFound(NotificationChannelNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.notification_channel_not_found"));
        pd.setProperty("error", "NOTIFICATION_CHANNEL_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(NotificationChannelConfigException.class)
    ProblemDetail handleConfig(NotificationChannelConfigException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.notification_channel_config_invalid"));
        pd.setProperty("error", "NOTIFICATION_CHANNEL_CONFIG_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(NotificationDeliveryException.class)
    ProblemDetail handleDelivery(NotificationDeliveryException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, msg("error.notification_delivery_failed"));
        pd.setProperty("error", "NOTIFICATION_DELIVERY_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(UserNotificationNotFoundException.class)
    ProblemDetail handleUserNotificationNotFound(UserNotificationNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.user_notification_not_found"));
        pd.setProperty("error", "USER_NOTIFICATION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
