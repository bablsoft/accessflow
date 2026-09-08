package com.bablsoft.accessflow.ai.internal.help;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the {@code .tar.gz} archives the release workflow publishes, so the reader under test is
 * fed real {@code ustar} bytes rather than a stub of itself. Deliberately written by hand: the point
 * of the exercise is that {@code HelpCorpusArchive} agrees with the on-the-wire format, and a helper
 * built on the same assumptions would prove nothing.
 */
final class TarGzFixtures {

    private static final int BLOCK = 512;

    private TarGzFixtures() {
    }

    /** The three bundle members of a classpath fixture directory, under a top-level folder. */
    static Map<String, byte[]> bundleFiles(String fixture) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        for (var name : new String[] {HelpCorpusBundle.MANIFEST_FILE, HelpCorpusBundle.CORPUS_FILE,
                HelpCorpusBundle.QUICK_REFERENCE_FILE}) {
            files.put(name, readFixture(fixture, name));
        }
        return files;
    }

    /** A gzipped tar of a fixture bundle, laid out as {@code tar czf … help-corpus} produces it. */
    static byte[] archiveOf(String fixture) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("help-corpus/", new byte[0]);
        bundleFiles(fixture).forEach((name, content) -> entries.put("help-corpus/" + name, content));
        return targz(entries);
    }

    /** A gzipped tar of arbitrary entries; a name ending in {@code /} is written as a directory. */
    static byte[] targz(Map<String, byte[]> entries) throws IOException {
        var tar = new ByteArrayOutputStream();
        for (var entry : entries.entrySet()) {
            boolean directory = entry.getKey().endsWith("/");
            tar.write(header(entry.getKey(), directory ? 0 : entry.getValue().length,
                    directory ? '5' : '0'));
            if (!directory) {
                tar.write(entry.getValue());
                tar.write(new byte[padding(entry.getValue().length)]);
            }
        }
        tar.write(new byte[BLOCK * 2]); // end-of-archive
        var gzipped = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(gzipped)) {
            out.write(tar.toByteArray());
        }
        return gzipped.toByteArray();
    }

    private static byte[] header(String name, int size, char typeFlag) {
        var header = new byte[BLOCK];
        write(header, 0, name, 100);
        write(header, 100, "0000644\0", 8);
        write(header, 108, "0000000\0", 8);
        write(header, 116, "0000000\0", 8);
        write(header, 124, String.format("%011o\0", size), 12);
        write(header, 136, "00000000000\0", 12);
        write(header, 148, "        ", 8); // checksum field counts as spaces while summing
        header[156] = (byte) typeFlag;
        write(header, 257, "ustar\0", 6);
        write(header, 263, "00", 2);
        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        write(header, 148, String.format("%06o\0 ", checksum), 8);
        return header;
    }

    private static void write(byte[] header, int offset, String value, int length) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static int padding(int size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private static byte[] readFixture(String fixture, String fileName) throws IOException {
        var path = "/help-corpus-fixtures/" + fixture + "/" + fileName;
        try (InputStream in = TarGzFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing test fixture " + path);
            }
            return in.readAllBytes();
        }
    }
}
