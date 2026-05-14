package com.bablsoft.accessflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.bablsoft.accessflow.core.api.SupportedLanguage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("messages*.properties translation parity")
class MessagesParityTest {

    private static final String BASE = "/i18n/messages.properties";

    private static Stream<SupportedLanguage> nonEnglishLocales() {
        return Arrays.stream(SupportedLanguage.values()).filter(l -> l != SupportedLanguage.EN);
    }

    @ParameterizedTest(name = "{0} has every key from the English baseline and no orphans")
    @MethodSource("nonEnglishLocales")
    void localeMatchesEnglishBaseline(SupportedLanguage language) throws IOException {
        Set<String> baselineKeys = loadKeys(BASE);
        String resource = resourceFor(language);
        Set<String> localeKeys = loadKeys(resource);

        Set<String> missing = new TreeSet<>(baselineKeys);
        missing.removeAll(localeKeys);
        Set<String> orphans = new TreeSet<>(localeKeys);
        orphans.removeAll(baselineKeys);

        assertThat(missing)
                .as("%s is missing %d translation keys present in messages.properties", resource, missing.size())
                .isEmpty();
        assertThat(orphans)
                .as("%s declares %d keys that do not exist in messages.properties", resource, orphans.size())
                .isEmpty();
    }

    private static String resourceFor(SupportedLanguage language) {
        return "/i18n/messages_" + language.locale().toString() + ".properties";
    }

    private static Set<String> loadKeys(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = MessagesParityTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("missing classpath resource %s", resource).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new TreeSet<>(props.stringPropertyNames());
    }
}
