package com.partqam.accessflow.notifications.internal.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("accessflow.notifications")
@Validated
public record NotificationsProperties(
        @NotNull URI publicBaseUrl,
        @NotNull Retry retry) {

    public NotificationsProperties {
        if (retry == null) {
            retry = Retry.defaults();
        }
    }

    public record Retry(Duration first, Duration second, Duration third) {

        public Retry {
            if (first == null) {
                first = Duration.ofSeconds(30);
            }
            if (second == null) {
                second = Duration.ofMinutes(2);
            }
            if (third == null) {
                third = Duration.ofMinutes(10);
            }
        }

        public static Retry defaults() {
            return new Retry(null, null, null);
        }
    }
}
