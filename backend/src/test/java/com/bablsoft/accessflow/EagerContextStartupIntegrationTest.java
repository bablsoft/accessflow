package com.bablsoft.accessflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructs every singleton at startup, which the rest of the suite no longer does.
 *
 * <p>{@code src/test/resources/application.properties} sets
 * {@code spring.main.lazy-initialization=true} because booting the whole application per context
 * dominated the suite's wall clock. What that costs is the startup failure: a bean that cannot be
 * constructed no longer fails the context load, it fails whichever test first touches it, reported
 * far from the actual cause. This class pins the property back to {@code false} so exactly one
 * context per run still builds the full graph up front and a broken definition fails here, by name.
 *
 * <p>Deliberately its own context: the override is what gives the guard its value, and the single
 * eager startup it costs is a fraction of what lazy initialization saves across the other ~136
 * integration-test classes.
 */
@SpringBootTest(properties = "spring.main.lazy-initialization=false")
@ImportTestcontainers(TestcontainersConfig.class)
class EagerContextStartupIntegrationTest {

    @Autowired ConfigurableApplicationContext applicationContext;

    /**
     * Reaching this method at all is most of the assertion — an unconstructable bean fails the
     * context load before any test runs.
     *
     * <p>The singleton count is what stops the guard rotting silently. If the override ever stops
     * outranking the suite-wide default, this class loads lazily like every other one and asserts
     * nothing; measured on this context, eager construction yields ~1642 singletons against 1634
     * definitions, where lazy yields ~846. Comparing against the definition count keeps that
     * meaningful without pinning an exact number the next migration would invalidate.
     */
    @Test
    void everySingletonIsConstructedAtStartup() {
        var beanFactory = applicationContext.getBeanFactory();

        assertThat(beanFactory.getSingletonCount())
                .as("lazy initialization was not overridden, so this guard proves nothing")
                .isGreaterThanOrEqualTo(applicationContext.getBeanDefinitionCount());
    }

}
