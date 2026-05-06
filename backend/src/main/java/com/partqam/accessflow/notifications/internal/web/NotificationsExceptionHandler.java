package com.partqam.accessflow.notifications.internal.web;

import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelNotFoundException;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice(basePackageClasses = NotificationsExceptionHandler.class)
class NotificationsExceptionHandler {

    @ExceptionHandler(NotificationChannelNotFoundException.class)
    ProblemDetail handleNotFound(NotificationChannelNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setProperty("error", "NOTIFICATION_CHANNEL_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(NotificationChannelConfigException.class)
    ProblemDetail handleConfig(NotificationChannelConfigException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "NOTIFICATION_CHANNEL_CONFIG_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(NotificationDeliveryException.class)
    ProblemDetail handleDelivery(NotificationDeliveryException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        pd.setProperty("error", "NOTIFICATION_DELIVERY_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
