package com.bablsoft.accessflow.ai.internal.help;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Reads the three help-corpus files out of the {@code help-corpus-<version>.tar.gz} the release
 * workflow publishes (AF-907).
 *
 * <p>Hand-rolled rather than pulled in from a compression library, because the whole job is "pick
 * three known file names out of a flat archive we produce ourselves". Nothing is ever written to
 * disk from an archive entry, so the classic tar hazards do not apply: an entry name is only ever
 * compared against a fixed allow-list of base names, never resolved as a path, so {@code ../} and
 * absolute names simply fail to match. Symlink, hard-link and device entries are skipped outright,
 * and the decompressed byte count is capped as it is read, so a zip bomb is refused rather than
 * buffered.
 *
 * <p>Only the plain {@code ustar} regular-file entries GNU {@code tar czf} writes for this content
 * are understood. A GNU long-name extension would need names over 100 characters, which
 * {@code help-corpus/corpus.jsonl} is not.
 */
final class HelpCorpusArchive {

    private static final int BLOCK = 512;
    private static final int NAME_OFFSET = 0;
    private static final int NAME_LENGTH = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPE_FLAG_OFFSET = 156;

    private HelpCorpusArchive() {
    }

    /**
     * Extracts the wanted entries, matched on base name so the archive's top-level directory does not
     * have to be guessed.
     *
     * @param archive        the gzipped tar bytes
     * @param wantedFileNames base names to keep; the first occurrence of each wins
     * @param maxTotalBytes  hard cap on decompressed bytes read, headers and padding included
     * @throws IOException the stream is not a readable gzipped tar, or exceeds the cap
     */
    static Map<String, byte[]> extract(byte[] archive, Set<String> wantedFileNames, long maxTotalBytes)
            throws IOException {
        var found = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            byte[] header;
            while ((header = readBlock(in)) != null) {
                var name = string(header, NAME_OFFSET, NAME_LENGTH);
                if (name.isEmpty()) {
                    break; // the two zero blocks that end an archive
                }
                long size = octal(header, SIZE_OFFSET, SIZE_LENGTH);
                // Every byte consumed counts, not just the declared payloads: an archive of nothing
                // but zero-length entries never grows a payload total, and would otherwise spin
                // through the whole decompressed stream — which is what the cap exists to stop.
                total += BLOCK + size + padding(size);
                if (total > maxTotalBytes) {
                    throw new IOException("archive expands past the " + maxTotalBytes
                            + "-byte limit; refusing to read it");
                }
                var content = readExactly(in, size);
                skip(in, padding(size));
                if (isRegularFile(header[TYPE_FLAG_OFFSET])) {
                    var baseName = name.substring(name.lastIndexOf('/') + 1);
                    if (wantedFileNames.contains(baseName)) {
                        found.putIfAbsent(baseName, content);
                    }
                }
            }
        }
        return found;
    }

    private static boolean isRegularFile(byte typeFlag) {
        return typeFlag == '0' || typeFlag == 0;
    }

    private static byte[] readBlock(InputStream in) throws IOException {
        var block = in.readNBytes(BLOCK);
        if (block.length == 0) {
            return null; // an archive truncated at a block boundary, with no end-of-archive marker
        }
        if (block.length < BLOCK) {
            throw new IOException("archive ends mid-header");
        }
        return block;
    }

    private static byte[] readExactly(InputStream in, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IOException("archive declares an unreadable entry size: " + size);
        }
        var content = in.readNBytes((int) size);
        if (content.length != size) {
            throw new IOException("archive ends mid-entry");
        }
        return content;
    }

    private static void skip(InputStream in, int count) throws IOException {
        if (count > 0 && in.readNBytes(count).length != count) {
            throw new IOException("archive ends mid-padding");
        }
    }

    private static int padding(long size) {
        return (int) ((BLOCK - (size % BLOCK)) % BLOCK);
    }

    private static String string(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] header, int offset, int length) throws IOException {
        var raw = string(header, offset, length).trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw, 8);
        } catch (NumberFormatException e) {
            throw new IOException("archive header holds an unreadable size field: '" + raw + "'", e);
        }
    }
}
