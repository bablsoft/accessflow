package com.bablsoft.accessflow.notifications.internal.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("accessflow.notifications")
@Validated
public record NotificationsProperties(
        @NotNull URI publicBaseUrl,
        @NotNull Retry retry,
        URI telegramApiBaseUrl,
        URI pagerDutyApiBaseUrl) {

    private static final URI DEFAULT_TELEGRAM_API_BASE_URL = URI.create("https://api.telegram.org/");
    private static final URI DEFAULT_PAGERDUTY_API_BASE_URL =
            URI.create("https://events.pagerduty.com/");

    public NotificationsProperties {
        if (retry == null) {
            retry = Retry.defaults();
        }
        if (telegramApiBaseUrl == null) {
            telegramApiBaseUrl = DEFAULT_TELEGRAM_API_BASE_URL;
        } else {
            var raw = telegramApiBaseUrl.toString();
            if (!raw.endsWith("/")) {
                telegramApiBaseUrl = URI.create(raw + "/");
            }
        }
        if (pagerDutyApiBaseUrl == null) {
            pagerDutyApiBaseUrl = DEFAULT_PAGERDUTY_API_BASE_URL;
        } else {
            var raw = pagerDutyApiBaseUrl.toString();
            if (!raw.endsWith("/")) {
                pagerDutyApiBaseUrl = URI.create(raw + "/");
            }
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
