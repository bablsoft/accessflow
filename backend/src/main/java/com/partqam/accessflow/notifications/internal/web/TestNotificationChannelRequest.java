package com.partqam.accessflow.notifications.internal.web;

import jakarta.validation.constraints.Email;

record TestNotificationChannelRequest(@Email String email) {
}
