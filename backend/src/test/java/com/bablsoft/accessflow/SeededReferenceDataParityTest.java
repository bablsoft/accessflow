package com.bablsoft.accessflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link DatabaseResetTestExecutionListener}, which truncates every table between test
 * classes and then restores only what {@link TestSystemRoleSeeder} knows about.
 *
 * <p>If a future migration seeds reference data into another table, that data would be silently
 * truncated after the first test class and every later class would run against a table the
 * application expects to be populated — a failure mode that surfaces far from its cause. This test
 * fails the build instead, so whoever adds the seed also extends the reseeder.
 */
class SeededReferenceDataParityTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern INSERT_INTO =
            Pattern.compile("INSERT\\s+INTO\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    /**
     * Tables a migration inserts into that {@link TestSystemRoleSeeder} restores after a truncate.
     */
    private static final Set<String> RESEEDED = Set.of("roles", "role_permissions");

    /**
     * Tables a migration inserts into where the INSERT is a data migration over pre-existing rows
     * ({@code INSERT ... SELECT ... FROM <table>}), not a reference-data seed. On the empty
     * database a test run starts from, these insert nothing, so there is nothing to restore.
     */
    private static final Set<String> DATA_MIGRATION_ONLY = Set.of("datasource_read_replicas");

    @Test
    @DisplayName("every table seeded by a migration is restored after the inter-class truncate")
    void everySeededTableIsReseeded() {
        var seeded = new TreeSet<String>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.filter(p -> p.toString().endsWith(".sql")).forEach(p -> {
                var matcher = INSERT_INTO.matcher(read(p));
                while (matcher.find()) {
                    seeded.add(matcher.group(1).toLowerCase(Locale.ROOT));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(seeded)
                .describedAs("A migration seeds a table that DatabaseResetTestExecutionListener "
                        + "truncates and does not restore. Either extend TestSystemRoleSeeder to "
                        + "re-insert it, or add it to DATA_MIGRATION_ONLY if the INSERT only "
                        + "rewrites pre-existing rows.")
                .isSubsetOf(union(RESEEDED, DATA_MIGRATION_ONLY));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
