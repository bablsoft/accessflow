package com.bablsoft.accessflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.bablsoft.accessflow.core.api.SupportedLanguage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("messages*.properties translation parity")
class MessagesParityTest {

    private static final String BASE = "/i18n/messages.properties";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)(?:,[^}]*)?}");

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

    /**
     * A key that carries positional arguments is run through {@link MessageFormat}, where a single
     * apostrophe is the <em>quote</em> character: an unpaired {@code '} swallows itself and turns
     * the rest of the pattern into a literal, so {@code l'action {1}} renders as {@code laction {1}}
     * — the argument silently never substituted. That is invisible to the parity check above, which
     * only compares key sets; it bit the French {@code routing.matched} keys during AF-967 and, once
     * guarded, surfaced 24 more pre-existing elisions across the catalogue.
     *
     * <p>Every locale is checked, not only French: the same trap exists in any language that elides
     * (Italian, Catalan) and in an English string that quotes a word.
     */
    @ParameterizedTest(name = "{0} keeps every placeholder through MessageFormat")
    @MethodSource("allLocales")
    void placeholdersSurviveMessageFormat(SupportedLanguage language) throws IOException {
        var resource = language == SupportedLanguage.EN ? BASE : resourceFor(language);
        var props = load(resource);
        var broken = new TreeSet<String>();
        for (var key : new TreeSet<>(props.stringPropertyNames())) {
            var pattern = props.getProperty(key);
            var placeholders = countPlaceholders(pattern);
            if (placeholders == 0) {
                continue;
            }
            MessageFormat format;
            try {
                format = new MessageFormat(pattern);
            } catch (IllegalArgumentException ex) {
                broken.add(key + " — unparseable: " + ex.getMessage());
                continue;
            }
            var args = stubArguments(format, placeholders);
            var rendered = format.format(args);
            for (var arg : args) {
                if (!rendered.contains(String.valueOf(arg))) {
                    broken.add(key + " — " + arg + " was not substituted: " + rendered);
                }
            }
        }
        assertThat(broken)
                .as("%s has MessageFormat patterns that drop an argument; double the apostrophes "
                        + "(l''action, not l'action)", resource)
                .isEmpty();
    }

    /** {@code {0}}…{@code {9}}, typed or not ({@code {0,number,#}}) — enough for every pattern the app uses. */
    private static int countPlaceholders(String pattern) {
        int highest = -1;
        var matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest + 1;
    }

    /**
     * A distinctive string per slot, except where the pattern types the slot as a number — a
     * {@link NumberFormat} refuses a string outright. {@code 700 + i} stays below every locale's
     * grouping threshold, so it renders as the same digits everywhere.
     */
    private static Object[] stubArguments(MessageFormat format, int placeholders) {
        var formats = format.getFormatsByArgumentIndex();
        var args = new Object[placeholders];
        Arrays.setAll(args, i -> i < formats.length && formats[i] instanceof NumberFormat
                ? 700 + i
                : "<arg" + i + ">");
        return args;
    }

    private static Stream<SupportedLanguage> allLocales() {
        return Arrays.stream(SupportedLanguage.values());
    }

    private static Properties load(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = MessagesParityTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("missing classpath resource %s", resource).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    private static String resourceFor(SupportedLanguage language) {
        return "/i18n/messages_" + language.locale().toString() + ".properties";
    }

    private static Set<String> loadKeys(String resource) throws IOException {
        return new TreeSet<>(load(resource).stringPropertyNames());
    }
}
