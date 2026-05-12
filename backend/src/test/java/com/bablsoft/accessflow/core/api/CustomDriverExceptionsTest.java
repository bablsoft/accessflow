package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct exception accessor coverage. The exception messages are read by ProblemDetail handlers
 * and the discriminator fields are surfaced as response properties; both branches need to be
 * exercised to satisfy the JaCoCo gate on small classes that the integration tests don't reach
 * via the getter path.
 */
class CustomDriverExceptionsTest {

    @Test
    void inUseExceptionCarriesDriverIdAndReferencedListImmutably() {
        var driverId = UUID.randomUUID();
        var ref = UUID.randomUUID();
        var ex = new CustomDriverInUseException(driverId, List.of(ref));

        assertThat(ex.driverId()).isEqualTo(driverId);
        assertThat(ex.referencedBy()).containsExactly(ref);
        assertThat(ex.getMessage())
                .contains(driverId.toString())
                .contains(ref.toString());
    }

    @Test
    void duplicateExceptionCarriesExistingIdAndSha() {
        var driverId = UUID.randomUUID();
        var sha = "a".repeat(64);
        var ex = new CustomDriverDuplicateException(driverId, sha);

        assertThat(ex.existingDriverId()).isEqualTo(driverId);
        assertThat(ex.jarSha256()).isEqualTo(sha);
        assertThat(ex.getMessage()).contains(sha).contains(driverId.toString());
    }

    @Test
    void notFoundExceptionCarriesDriverId() {
        var driverId = UUID.randomUUID();
        var ex = new CustomDriverNotFoundException(driverId);

        assertThat(ex.driverId()).isEqualTo(driverId);
        assertThat(ex.getMessage()).contains(driverId.toString());
    }

    @Test
    void tooLargeExceptionCarriesActualAndMaxBytes() {
        var ex = new CustomDriverTooLargeException(123_456L, 100L);

        assertThat(ex.actualBytes()).isEqualTo(123_456L);
        assertThat(ex.maxBytes()).isEqualTo(100L);
        assertThat(ex.getMessage()).contains("123456").contains("100");
    }

    @Test
    void checksumMismatchExceptionCarriesExpectedAndActual() {
        var expected = "a".repeat(64);
        var actual = "b".repeat(64);
        var ex = new CustomDriverChecksumMismatchException(expected, actual);

        assertThat(ex.expectedSha256()).isEqualTo(expected);
        assertThat(ex.actualSha256()).isEqualTo(actual);
        assertThat(ex.getMessage()).contains(expected).contains(actual);
    }

    @Test
    void invalidJarExceptionMessageOnlyConstructor() {
        var ex = new CustomDriverInvalidJarException("com.bogus.NotADriver",
                "driver class not found");

        assertThat(ex.driverClass()).isEqualTo("com.bogus.NotADriver");
        assertThat(ex.getMessage()).isEqualTo("driver class not found");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void invalidJarExceptionMessageAndCauseConstructor() {
        var cause = new ClassNotFoundException("com.bogus.NotADriver");
        var ex = new CustomDriverInvalidJarException("com.bogus.NotADriver",
                "load failed", cause);

        assertThat(ex.driverClass()).isEqualTo("com.bogus.NotADriver");
        assertThat(ex.getMessage()).isEqualTo("load failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
