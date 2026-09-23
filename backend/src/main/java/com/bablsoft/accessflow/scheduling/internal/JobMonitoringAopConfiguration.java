package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;

/**
 * Registers {@link ScheduledJobExecutionAdvisor}. Infrastructure role and a static factory method so
 * the advisor is available before ordinary beans are proxied; the recorder and properties are
 * resolved lazily through {@link ObjectProvider}s for the same reason.
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class JobMonitoringAopConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ScheduledJobExecutionAdvisor scheduledJobExecutionAdvisor(
            ObjectProvider<JobExecutionRecorder> recorder,
            ObjectProvider<JobMonitoringProperties> properties,
            ObjectProvider<Environment> environment) {
        return new ScheduledJobExecutionAdvisor(recorder, properties, environment);
    }
}
