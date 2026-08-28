package com.bablsoft.accessflow;

import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

/**
 * Puts the JVM-wide test JWT and encryption keys into every test context's {@code Environment}
 * before it refreshes.
 *
 * <p>Registered globally in {@code META-INF/spring.factories}, so no test class declares anything.
 * That is the whole point: the suite used to build <b>121 Spring contexts for 124 integration
 * tests</b> (631 s, 46 % of the run) because each class carried its own
 * {@code @DynamicPropertySource}, and {@code DynamicPropertiesContextCustomizer} keys the context
 * cache on the {@code Set<Method>} it finds on the test class hierarchy — a per-class method is a
 * guaranteed cache miss.
 *
 * <p>{@link TestKeysContextCustomizer} is value-equal to every other instance, so it contributes
 * one <i>stable</i> key component shared by all test classes rather than a per-class one.
 *
 * <p><b>Why not a {@code @DynamicPropertySource} on {@link TestcontainersConfig}?</b> That also
 * costs no cache key — {@code DynamicPropertySourceMethodsImporter} turns it into a
 * {@code DynamicPropertyRegistrar} <i>bean</i> — but a bean is applied during
 * {@code finishBeanFactoryInitialization}, which is too late for a servlet context: with
 * {@code webEnvironment = RANDOM_PORT}, {@code ServletWebServerApplicationContext.onRefresh()}
 * starts Tomcat and instantiates the security filter chain first, so {@code jwtServiceImpl} would
 * read an empty {@code accessflow.jwt.private-key} and fail with "Missing key encoding". A
 * {@code ContextCustomizer} runs before refresh, so it covers both web and mock contexts.
 */
public class TestKeysContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return new TestKeysContextCustomizer();
    }

    static final class TestKeysContextCustomizer implements ContextCustomizer {

        private static final String SOURCE_NAME = "accessflowTestKeys";

        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                                     MergedContextConfiguration mergedConfig) {
            // addFirst: these are not meant to be overridden per class, matching the behaviour the
            // per-class @DynamicPropertySource methods had (DynamicValuesPropertySource also
            // outranked @SpringBootTest(properties = ...)). application.yml resolves both keys from
            // env vars that are unset under test, so without this they are empty strings.
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    SOURCE_NAME,
                    Map.of("accessflow.jwt.private-key", TestKeys.JWT_PRIVATE_KEY_PEM,
                            "accessflow.encryption-key", TestKeys.ENCRYPTION_KEY)));
        }

        // Value equality keeps this a single, stable component of every context cache key.
        @Override
        public boolean equals(Object other) {
            return other instanceof TestKeysContextCustomizer;
        }

        @Override
        public int hashCode() {
            return TestKeysContextCustomizer.class.hashCode();
        }
    }
}
