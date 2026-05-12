package com.bablsoft.accessflow.notifications.internal.web;

import jakarta.validation.constraints.Email;

record TestNotificationChannelRequest(
        @Email(message = "{validation.email.invalid}") String email) {
}
