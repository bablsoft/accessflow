package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader is fed real {@code ustar} bytes, because the only thing worth proving about it is that
 * it agrees with what GNU {@code tar czf} writes. The rest of these cases are the ones that decide
 * whether a hostile archive can do anything: a traversal name, a symlink entry, and an archive that
 * expands far past what it claims to be.
 */
class HelpCorpusArchiveTest {

    private static final Set<String> WANTED = Set.of(HelpCorpusBundle.MANIFEST_FILE,
            HelpCorpusBundle.CORPUS_FILE, HelpCorpusBundle.QUICK_REFERENCE_FILE);
    private static final long CAP = 1024 * 1024;

    @Test
    void readsTheThreeBundleMembersOutOfAPublishedArchive() throws IOException {
        var extracted = HelpCorpusArchive.extract(TarGzFixtures.archiveOf("good"), WANTED, CAP);

        assertThat(extracted).containsOnlyKeys(HelpCorpusBundle.MANIFEST_FILE,
                HelpCorpusBundle.CORPUS_FILE, HelpCorpusBundle.QUICK_REFERENCE_FILE);
        assertThat(new String(extracted.get(HelpCorpusBundle.MANIFEST_FILE), StandardCharsets.UTF_8))
                .contains("\"corpusVersion\": \"0d746008cf42\"");
    }

    @Test
    void readsAnArchiveGnuTarActuallyWrote() throws IOException {
        // gnu-tar-good.tar.gz is `tar czf` output from the CI runner's own tar, committed verbatim.
        // TarGzFixtures and the reader share this file's author, so without it the round trip would
        // only ever prove the two agree with each other.
        byte[] archive;
        try (var in = getClass().getResourceAsStream("/help-corpus-fixtures/gnu-tar-good.tar.gz")) {
            archive = in.readAllBytes();
        }

        var extracted = HelpCorpusArchive.extract(archive, WANTED, CAP);

        assertThat(extracted).containsOnlyKeys(HelpCorpusBundle.MANIFEST_FILE,
                HelpCorpusBundle.CORPUS_FILE, HelpCorpusBundle.QUICK_REFERENCE_FILE);
        assertThat(new String(extracted.get(HelpCorpusBundle.MANIFEST_FILE), StandardCharsets.UTF_8))
                .contains("\"corpusVersion\": \"0d746008cf42\"");
    }

    @Test
    void ignoresEverythingItWasNotAskedFor() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("help-corpus/", new byte[0]);
        entries.put("help-corpus/README.md", "not a bundle member".getBytes(StandardCharsets.UTF_8));
        entries.put("help-corpus/manifest.json", "{}".getBytes(StandardCharsets.UTF_8));

        var extracted = HelpCorpusArchive.extract(TarGzFixtures.targz(entries), WANTED, CAP);

        assertThat(extracted).containsOnlyKeys(HelpCorpusBundle.MANIFEST_FILE);
    }

    @Test
    void neverMatchesATraversalName() throws IOException {
        // Names are compared, never resolved, so "../../etc/manifest.json" simply is not
        // "manifest.json" — there is no path for it to escape along.
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("../../etc/manifest.json", "owned".getBytes(StandardCharsets.UTF_8));
        entries.put("/absolute/corpus.jsonl", "owned".getBytes(StandardCharsets.UTF_8));

        var extracted = HelpCorpusArchive.extract(TarGzFixtures.targz(entries), WANTED, CAP);

        assertThat(extracted).containsOnlyKeys(HelpCorpusBundle.MANIFEST_FILE,
                HelpCorpusBundle.CORPUS_FILE);
        assertThat(new String(extracted.get(HelpCorpusBundle.MANIFEST_FILE), StandardCharsets.UTF_8))
                .isEqualTo("owned");
        // ...and the bytes still have to survive HelpCorpusBundle's digest checks, which "owned"
        // will not. Matching a base name is not the same as trusting it.
    }

    @Test
    void skipsEntriesThatAreNotRegularFiles() throws IOException {
        var tar = new ByteArrayOutputStream();
        tar.write(symlinkHeader("help-corpus/manifest.json", "/etc/passwd"));
        tar.write(new byte[512 * 2]);
        var gzipped = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(gzipped)) {
            out.write(tar.toByteArray());
        }

        assertThat(HelpCorpusArchive.extract(gzipped.toByteArray(), WANTED, CAP)).isEmpty();
    }

    @Test
    void refusesAnArchiveThatExpandsPastTheCap() throws IOException {
        var entries = Map.of("help-corpus/corpus.jsonl", new byte[64 * 1024]);

        assertThatThrownBy(() -> HelpCorpusArchive.extract(TarGzFixtures.targz(entries), WANTED, 4096))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expands past the 4096-byte limit");
    }

    @Test
    void refusesBytesThatAreNotAGzipStreamAtAll() {
        assertThatThrownBy(() -> HelpCorpusArchive.extract("<html>404</html>"
                .getBytes(StandardCharsets.UTF_8), WANTED, CAP))
                .isInstanceOf(IOException.class);
    }

    @Test
    void refusesAnArchiveTruncatedMidEntry() throws IOException {
        // A truncated tar inside an intact gzip container is what a half-written mirror serves: the
        // header still declares 1000 bytes, and only 600 of them arrive.
        var tar = gunzip(TarGzFixtures.targz(Map.of("help-corpus/corpus.jsonl", new byte[1000])));
        var truncated = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(truncated)) {
            out.write(Arrays.copyOf(tar, 512 + 600));
        }

        assertThatThrownBy(() -> HelpCorpusArchive.extract(truncated.toByteArray(), WANTED, CAP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ends mid-entry");
    }

    @Test
    void toleratesAnArchiveWithNoEndOfArchiveMarker() throws IOException {
        // GNU tar always writes the two zero blocks, but a stream cut at an exact block boundary is
        // indistinguishable from a well-formed archive until it simply stops. Whatever was read in
        // full is still usable, so this ends the loop rather than failing.
        var tar = gunzip(TarGzFixtures.archiveOf("good"));
        var truncated = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(truncated)) {
            out.write(Arrays.copyOf(tar, tar.length - 1024));
        }

        var extracted = HelpCorpusArchive.extract(truncated.toByteArray(), WANTED, CAP);

        assertThat(extracted).containsOnlyKeys(HelpCorpusBundle.MANIFEST_FILE,
                HelpCorpusBundle.CORPUS_FILE, HelpCorpusBundle.QUICK_REFERENCE_FILE);
    }

    @Test
    void refusesAnArchiveTruncatedMidHeader() throws IOException {
        var tar = gunzip(TarGzFixtures.targz(Map.of("help-corpus/corpus.jsonl", new byte[10])));

        assertThatThrownBy(() -> HelpCorpusArchive.extract(regzip(Arrays.copyOf(tar, 512 + 512 + 200)),
                WANTED, CAP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ends mid-header");
    }

    @Test
    void refusesAnArchiveTruncatedMidPadding() throws IOException {
        // 10 bytes of payload means 502 bytes of padding; stopping inside it leaves the next header
        // unreadable, so it cannot be treated as a clean end.
        var tar = gunzip(TarGzFixtures.targz(Map.of("help-corpus/corpus.jsonl", new byte[10])));

        assertThatThrownBy(() -> HelpCorpusArchive.extract(regzip(Arrays.copyOf(tar, 512 + 10 + 100)),
                WANTED, CAP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ends mid-padding");
    }

    @Test
    void refusesAnEntrySizeTooLargeToRead() throws IOException {
        // 0o77777777777 is ~8 GB — larger than an int, so it could never be read into an array, and
        // arithmetic on it must not be attempted either.
        assertThatThrownBy(() -> HelpCorpusArchive.extract(
                archiveWithRawSizeField("77777777777"), WANTED, Long.MAX_VALUE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unreadable entry size");
    }

    @Test
    void refusesAHeaderWhoseSizeFieldIsNotOctal() throws IOException {
        assertThatThrownBy(() -> HelpCorpusArchive.extract(
                archiveWithRawSizeField("notanumber "), WANTED, CAP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unreadable size field");
    }

    @Test
    void readsAnEntryWhoseSizeFieldIsBlank() throws IOException {
        // Some writers leave the field empty for a zero-length entry rather than writing zeros.
        var extracted = HelpCorpusArchive.extract(archiveWithRawSizeField("           "), WANTED, CAP);

        assertThat(extracted).containsOnlyKeys(HelpCorpusBundle.CORPUS_FILE);
        assertThat(extracted.get(HelpCorpusBundle.CORPUS_FILE)).isEmpty();
    }

    /** A one-entry archive whose 12-byte size field is overwritten with arbitrary bytes. */
    private static byte[] archiveWithRawSizeField(String rawSize) throws IOException {
        var tar = gunzip(TarGzFixtures.targz(Map.of("help-corpus/corpus.jsonl", new byte[0])));
        var bytes = rawSize.getBytes(StandardCharsets.UTF_8);
        Arrays.fill(tar, 124, 136, (byte) 0);
        System.arraycopy(bytes, 0, tar, 124, Math.min(bytes.length, 12));
        return regzip(tar);
    }

    private static byte[] regzip(byte[] tar) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(tar);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] gzipped) throws IOException {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return in.readAllBytes();
        }
    }

    private static byte[] symlinkHeader(String name, String target) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(name, new byte[0]);
        var header = Arrays.copyOf(gunzip(TarGzFixtures.targz(entries)), 512);
        header[156] = '2'; // symlink
        System.arraycopy(target.getBytes(StandardCharsets.UTF_8), 0, header, 157, target.length());
        return header;
    }
}
