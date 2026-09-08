package com.bablsoft.accessflow.ai.internal.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The bundled documentation corpus the in-app help agent answers from (AF-902, epic AF-899), loaded
 * once from the classpath at {@code help-corpus/**} — put there by the Maven resources plugin from the
 * repo-root {@code help-corpus/} folder that {@code .github/scripts/build-help-corpus.mjs} generates
 * (AF-900). Bundling it is what makes an install answer from documentation for the version it is
 * actually running, air-gapped and with no outbound call.
 *
 * <p><strong>Loading never fails the application context.</strong> A hard fail-fast on a feature
 * nobody has enabled is worse than the bug it would catch, so a missing, truncated or tampered bundle
 * is logged at {@code ERROR} and leaves this component {@link #available() unavailable}; the
 * <em>enable</em> path refuses instead, with {@code HELP_CORPUS_MISSING}. That is the opposite of
 * {@code ConnectorCatalog}, which is fail-fast because no deployment can serve a query without it.
 *
 * <p>What is verified, in order: the manifest parses; its {@code schemaVersion} is one this code
 * understands (a newer bundle would otherwise be read with fields silently dropped —
 * {@code corpus.jsonl} is a stream of independent objects, so nothing else would notice); the
 * {@code corpus.jsonl} bytes hash to the manifest's {@code sha256}; the manifest's
 * {@code corpusVersion} really is that digest's first 12 characters; the line count matches
 * {@code chunkCount}; and {@code quick-reference.txt} hashes to {@code quickReferenceSha256}.
 *
 * <p>The active corpus is not necessarily the bundled one. {@link HelpCorpusRemoteRefresher} may
 * {@link #activateRefreshed(Map) swap in} a newer corpus published between releases (AF-907, off by
 * default). It goes through <em>this</em> verification, unchanged and in full, and the swap happens
 * only once every check has passed — so a corrupt, truncated or newer-schema remote bundle can never
 * take a working bundled corpus out of service.
 */
@Component
public class HelpCorpusBundle {

    /**
     * Highest {@code manifest.schemaVersion} this code can read. Bump it only together with the
     * fields it adds — refusing a newer bundle is the point.
     */
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    static final String DEFAULT_BASE_PATH = "classpath:help-corpus/";
    static final String MANIFEST_FILE = "manifest.json";
    static final String CORPUS_FILE = "corpus.jsonl";
    static final String QUICK_REFERENCE_FILE = "quick-reference.txt";

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusBundle.class);
    private static final int CORPUS_VERSION_LENGTH = 12;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final AtomicReference<LoadedBundle> active = new AtomicReference<>();
    private final String bundledCorpusVersion;
    private final String bundledLoadError;

    @Autowired
    public HelpCorpusBundle(ResourceLoader resourceLoader) {
        this(resourceLoader, DEFAULT_BASE_PATH);
    }

    /** Test seam: the same verification against a fixture bundle somewhere else on the classpath. */
    HelpCorpusBundle(ResourceLoader resourceLoader, String basePath) {
        String version = null;
        String error = null;
        try {
            var loadedBundle = verify(name -> read(resourceLoader, basePath, name));
            version = loadedBundle.corpusVersion();
            active.set(loadedBundle);
            log.info("Loaded help documentation corpus version {} ({} chunks)", version,
                    loadedBundle.chunks().size());
        } catch (IOException | JacksonException | IllegalStateException | NoSuchAlgorithmException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Help documentation corpus at {} could not be loaded; the help agent cannot be "
                    + "enabled until this is fixed: {}", basePath, error, e);
        }
        this.bundledCorpusVersion = version;
        this.bundledLoadError = error;
    }

    /** Whether a corpus loaded and verified. Everything else is only meaningful when this is true. */
    public boolean available() {
        return active.get() != null;
    }

    /** Why the bundle is unavailable, for logs and {@code index_error} — {@code null} when it loaded. */
    public String loadError() {
        return available() ? null : bundledLoadError;
    }

    /**
     * The corpus version compiled into this build, whatever a remote refresh has since activated.
     * Diagnostics only — indexing keys off {@link #corpusVersion()}.
     */
    public String bundledCorpusVersion() {
        return bundledCorpusVersion;
    }

    /**
     * {@code sha256(corpus.jsonl)[0..12]}. Content-derived rather than the application version, so
     * re-ingestion is an idempotent string compare across replicas and restarts, and a documentation
     * typo fix does not re-embed an unchanged corpus.
     */
    public String corpusVersion() {
        var current = active.get();
        return current == null ? null : current.corpusVersion();
    }

    public int chunkCount() {
        return chunks().size();
    }

    public List<HelpCorpusChunk> chunks() {
        var current = active.get();
        return current == null ? List.of() : current.chunks();
    }

    /**
     * The orientation block substituted for retrieved context whenever retrieval is unavailable — no
     * pgvector, no embedding provider, or an Anthropic-only install. Consumed by the chat runtime.
     */
    public String quickReference() {
        var current = active.get();
        return current == null ? null : current.quickReference();
    }

    /**
     * Verifies a corpus fetched from the release index and, only if every check passes, makes it the
     * one this process answers from (AF-907).
     *
     * @param files the three bundle members by file name, as extracted from the published archive
     * @return the {@code corpusVersion} now active
     * @throws IllegalStateException the bundle is incomplete, fails a digest, or declares a
     *         {@code schemaVersion} this build cannot read — the previously active corpus is kept
     */
    public String activateRefreshed(Map<String, byte[]> files) {
        LoadedBundle refreshed;
        try {
            refreshed = verify(name -> {
                var bytes = files.get(name);
                if (bytes == null) {
                    throw new IllegalStateException(name + " is missing from the published archive");
                }
                return bytes;
            });
        } catch (IOException | JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
        active.set(refreshed);
        log.info("Activated refreshed help documentation corpus version {} ({} chunks); the corpus "
                + "bundled in this build is version {}", refreshed.corpusVersion(),
                refreshed.chunks().size(), bundledCorpusVersion);
        return refreshed.corpusVersion();
    }

    private static LoadedBundle verify(ByteSource source)
            throws IOException, NoSuchAlgorithmException {
        var manifest = MAPPER.readValue(source.read(MANIFEST_FILE), HelpCorpusManifest.class);
        if (manifest == null) {
            throw new IllegalStateException(MANIFEST_FILE + " is the JSON literal null");
        }
        if (manifest.schemaVersion() > SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("manifest schemaVersion " + manifest.schemaVersion()
                    + " is newer than the supported " + SUPPORTED_SCHEMA_VERSION
                    + "; upgrade AccessFlow to read this corpus");
        }
        var corpusBytes = source.read(CORPUS_FILE);
        var digest = sha256(corpusBytes);
        requireDigest(CORPUS_FILE, manifest.sha256(), digest);
        var expectedVersion = digest.substring(0, CORPUS_VERSION_LENGTH);
        if (!expectedVersion.equals(manifest.corpusVersion())) {
            throw new IllegalStateException("manifest corpusVersion '" + manifest.corpusVersion()
                    + "' is not the first " + CORPUS_VERSION_LENGTH + " characters of the "
                    + CORPUS_FILE + " digest ('" + expectedVersion + "')");
        }
        var chunks = parseChunks(corpusBytes);
        if (chunks.size() != manifest.chunkCount()) {
            throw new IllegalStateException(CORPUS_FILE + " holds " + chunks.size()
                    + " chunks but the manifest declares " + manifest.chunkCount());
        }
        var quickReferenceBytes = source.read(QUICK_REFERENCE_FILE);
        requireDigest(QUICK_REFERENCE_FILE, manifest.quickReferenceSha256(),
                sha256(quickReferenceBytes));
        return new LoadedBundle(manifest.corpusVersion(), List.copyOf(chunks),
                new String(quickReferenceBytes, StandardCharsets.UTF_8));
    }

    private static List<HelpCorpusChunk> parseChunks(byte[] corpusBytes) {
        var text = new String(corpusBytes, StandardCharsets.UTF_8);
        var chunks = new ArrayList<HelpCorpusChunk>();
        for (var line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            var chunk = MAPPER.readValue(line, HelpCorpusChunk.class);
            if (chunk == null || chunk.id() == null || chunk.text() == null || chunk.text().isBlank()) {
                throw new IllegalStateException(CORPUS_FILE + " holds a chunk with no id or no text");
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private static byte[] read(ResourceLoader resourceLoader, String basePath, String fileName)
            throws IOException {
        Resource resource = resourceLoader.getResource(basePath + fileName);
        if (!resource.exists()) {
            throw new IllegalStateException(fileName + " is missing from " + basePath);
        }
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0) {
            throw new IllegalStateException(fileName + " in " + basePath + " is empty");
        }
        return bytes;
    }

    private static void requireDigest(String fileName, String expected, String actual) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException(fileName + " digest " + actual
                    + " does not match the manifest's " + expected);
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Where the three bundle members come from — the classpath, or a downloaded archive. */
    @FunctionalInterface
    private interface ByteSource {
        byte[] read(String fileName) throws IOException;
    }

    private record LoadedBundle(String corpusVersion, List<HelpCorpusChunk> chunks,
                                String quickReference) {
    }
}
