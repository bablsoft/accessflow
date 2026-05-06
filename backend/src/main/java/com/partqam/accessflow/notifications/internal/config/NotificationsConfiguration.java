package com.partqam.accessflow.notifications.internal.config;

import com.slack.api.Slack;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationsProperties.class)
class NotificationsConfiguration {

    @Bean
    RestClient notificationsRestClient() {
        return RestClient.create();
    }

    @Bean
    TaskScheduler notificationsTaskScheduler() {
        var scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("accessflow-notifications-");
        return scheduler;
    }

    @Bean
    Slack slack() {
        return Slack.getInstance();
    }
}
