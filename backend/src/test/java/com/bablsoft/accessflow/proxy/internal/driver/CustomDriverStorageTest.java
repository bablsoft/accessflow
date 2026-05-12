package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.CustomDriverChecksumMismatchException;
import com.bablsoft.accessflow.core.api.CustomDriverTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomDriverStorageTest {

    @TempDir
    Path cacheDir;

    private CustomDriverStorage storage;

    @BeforeEach
    void setUp() {
        storage = new CustomDriverStorage(new DriverProperties(cacheDir, null, false));
    }

    @Test
    void storeWritesJarAndReturnsSha256AndSize() throws Exception {
        var orgId = UUID.randomUUID();
        var driverId = UUID.randomUUID();
        var bytes = "fake-jar-content".getBytes();
        var sha = sha256(bytes);

        var stored = storage.store(orgId, driverId, sha, 1024, new ByteArrayInputStream(bytes));

        assertThat(stored.sizeBytes()).isEqualTo(bytes.length);
        assertThat(stored.sha256()).isEqualToIgnoringCase(sha);
        assertThat(Files.isRegularFile(stored.absolutePath())).isTrue();
        assertThat(stored.relativePath()).startsWith("custom" + java.io.File.separator + orgId);
    }

    @Test
    void storeAtomicallyMovesIntoFinalLocationWithDriverIdFilename() throws Exception {
        var orgId = UUID.randomUUID();
        var driverId = UUID.randomUUID();
        var bytes = new byte[]{1, 2, 3, 4, 5};

        var stored = storage.store(orgId, driverId, sha256(bytes), 1024,
                new ByteArrayInputStream(bytes));

        assertThat(stored.absolutePath().getFileName().toString()).isEqualTo(driverId + ".jar");
        assertThat(Files.list(stored.absolutePath().getParent())
                .filter(p -> p.getFileName().toString().endsWith(".part")))
                .as("no temp .part file should remain after successful store")
                .isEmpty();
    }

    @Test
    void storeRejectsAndDeletesTempOnChecksumMismatch() {
        var orgId = UUID.randomUUID();
        var driverId = UUID.randomUUID();
        var bytes = new byte[]{1, 2, 3};
        var wrongSha = "0".repeat(64);

        assertThatThrownBy(() -> storage.store(orgId, driverId, wrongSha, 1024,
                new ByteArrayInputStream(bytes)))
                .isInstanceOf(CustomDriverChecksumMismatchException.class);

        // No .jar file should remain.
        var orgDir = cacheDir.resolve("custom").resolve(orgId.toString());
        assertThat(orgDir.resolve(driverId + ".jar")).doesNotExist();
        assertThat(orgDir.resolve(driverId + ".jar.part")).doesNotExist();
    }

    @Test
    void storeRejectsOversizedContent() {
        var orgId = UUID.randomUUID();
        var driverId = UUID.randomUUID();
        var bytes = new byte[200];

        assertThatThrownBy(() -> storage.store(orgId, driverId, sha256(bytes), 50,
                new ByteArrayInputStream(bytes)))
                .isInstanceOf(CustomDriverTooLargeException.class)
                .satisfies(ex -> {
                    var tooLarge = (CustomDriverTooLargeException) ex;
                    assertThat(tooLarge.maxBytes()).isEqualTo(50);
                });
    }

    @Test
    void resolveReturnsAbsolutePathRelativeToCacheDir() {
        var p = storage.resolve("custom/org/abc.jar");

        assertThat(p.toString()).startsWith(cacheDir.toString());
        assertThat(p.toString()).endsWith("custom" + java.io.File.separator + "org"
                + java.io.File.separator + "abc.jar");
    }

    @Test
    void deleteIsIdempotentWhenFileMissing() {
        // Should not throw when target does not exist.
        storage.delete("custom/missing/" + UUID.randomUUID() + ".jar");
    }

    @Test
    void existsReturnsTrueOnlyAfterStore() throws Exception {
        var orgId = UUID.randomUUID();
        var driverId = UUID.randomUUID();
        var bytes = new byte[]{42};

        var stored = storage.store(orgId, driverId, sha256(bytes), 1024,
                new ByteArrayInputStream(bytes));

        assertThat(storage.exists(stored.relativePath())).isTrue();
        storage.delete(stored.relativePath());
        assertThat(storage.exists(stored.relativePath())).isFalse();
    }

    private static String sha256(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
