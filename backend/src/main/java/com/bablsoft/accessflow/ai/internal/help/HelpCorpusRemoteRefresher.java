package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpCorpusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks up a documentation corpus published between application releases (AF-907).
 *
 * <p><strong>Off unless an operator asks for it.</strong> The corpus in the jar is always exactly the
 * documentation for the running version; a remote corpus describing a UI this install does not have
 * is a worse failure than the staleness it fixes, because the agent will state it confidently. What
 * this buys, when switched on, is a documentation <em>correction</em> reaching an install that is not
 * being upgraded.
 *
 * <p>The pipeline is {@code DriverJarCache}'s, for the same reason it exists there: a pinned SHA-256
 * is the only thing that makes downloading executable-adjacent content acceptable. Fetch the index,
 * compare its {@code corpusVersion} against the one already active, stream the archive to a
 * {@code .part} sibling under a hard size cap, hash the completed file, delete it on any mismatch,
 * and {@code ATOMIC_MOVE} it into place only once it is proven. Verification of the corpus
 * <em>inside</em> the archive — manifest digests, chunk count, and the {@code schemaVersion} refusal
 * that stops a newer bundle being read with fields silently dropped — is
 * {@link HelpCorpusBundle#activateRefreshed} and is not duplicated here.
 *
 * <p><strong>No failure here can cost an install its help agent.</strong> Every path out of
 * {@link #refresh()} that is not a successful activation logs at {@code WARN} and leaves the bundled
 * corpus exactly as it was: an unreachable host, a non-2xx answer, an oversized or truncated body, a
 * checksum mismatch, an unwritable cache directory, a corrupt archive, a corpus from a newer
 * AccessFlow. The one thing that never happens is a throw — the caller is the startup indexing pass.
 *
 * <p><strong>It is per replica, not per cluster.</strong> Each JVM holds its own corpus in memory, so
 * there is nothing to share and no lock to take; the ingestion that follows is what is locked. A
 * replica whose refresh fails keeps answering from the bundled corpus, and — exactly as during a
 * rolling upgrade — may re-index a scope a refreshed replica has already indexed. That converges as
 * soon as both hold the same corpus, and is why this is opt-in rather than default.
 */
@Component
public class HelpCorpusRemoteRefresher {

    /** A pointer file naming one bundle. Anything larger is not the file we expect. */
    static final int MAX_INDEX_BYTES = 64 * 1024;

    /** The published archive is a few hundred KB; the cap is what stops a hostile mirror. */
    static final long MAX_ARCHIVE_BYTES = 16L * 1024 * 1024;

    /** Decompressed cap, checked while reading, so an archive cannot expand into memory unbounded. */
    static final long MAX_EXTRACTED_BYTES = 64L * 1024 * 1024;

    static final Set<String> BUNDLE_FILES = Set.of(HelpCorpusBundle.MANIFEST_FILE,
            HelpCorpusBundle.CORPUS_FILE, HelpCorpusBundle.QUICK_REFERENCE_FILE);

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusRemoteRefresher.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Pattern CORPUS_VERSION = Pattern.compile("[0-9a-f]{12}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final HelpCorpusProperties properties;
    private final HelpCorpusBundle bundle;
    private volatile HttpClient httpClient;

    HelpCorpusRemoteRefresher(HelpCorpusProperties properties, HelpCorpusBundle bundle) {
        this.properties = properties;
        this.bundle = bundle;
    }

    /**
     * Built on first use, not in the constructor: an {@code HttpClient} owns a selector thread, and
     * the default-off majority of installs would pay for one they never make a request with.
     */
    private HttpClient httpClient() {
        var client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Brings this replica's corpus up to whatever the release index names, when asked to. Never
     * throws, and never leaves the process worse off than it started.
     */
    public void refresh() {
        if (!properties.remoteRefreshEnabled()) {
            log.debug("Remote help corpus refresh is off; answering from the bundled corpus");
            return;
        }
        if (properties.offline()) {
            log.info("Remote help corpus refresh is configured but offline mode is on; no request "
                    + "will be made and the bundled corpus stays in use");
            return;
        }
        try {
            attemptRefresh();
        } catch (IOException | InterruptedException | RuntimeException e) {
            // Deliberately broad, and the documented kind of place for it: this is the top-level
            // swallow for work nobody is waiting on. HttpRequest.newBuilder alone throws
            // IllegalArgumentException for a URL that parses but has no usable authority, and
            // letting that escape would take the caller's indexing pass down with it.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Remote help corpus refresh from {} failed; keeping corpus version {}: {}",
                    properties.indexUrl(), bundle.corpusVersion(), reason(e));
            log.debug("Remote help corpus refresh failure detail", e);
        }
    }

    private void attemptRefresh() throws IOException, InterruptedException {
        var index = fetchIndex();
        if (index.corpusVersion().equals(bundle.corpusVersion())) {
            log.debug("Published help corpus is version {}, which is already active; nothing to do",
                    index.corpusVersion());
            return;
        }
        log.info("Published help corpus version {} (release {}) differs from the active {}; fetching",
                index.corpusVersion(), index.version(), bundle.corpusVersion());
        var archive = Files.readAllBytes(ensureCachedArchive(index));
        var files = HelpCorpusArchive.extract(archive, BUNDLE_FILES, MAX_EXTRACTED_BYTES);
        var activated = bundle.activateRefreshed(files);
        if (!activated.equals(index.corpusVersion())) {
            // The archive hashed to the pinned value, so it is the artifact the publisher signed for
            // — the index's own corpusVersion field is what is wrong. Worth saying out loud, but not
            // worth discarding a verified corpus over.
            log.warn("Published index claims help corpus version {} but the archive contains {}",
                    index.corpusVersion(), activated);
        }
    }

    /** The pointer file, capped on the stream rather than after parsing. */
    private CorpusIndex fetchIndex() throws IOException, InterruptedException {
        var response = httpClient().send(get(properties.indexUrl()),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = response.body()) {
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("help corpus index answered HTTP " + response.statusCode());
            }
            body = readCapped(in, MAX_INDEX_BYTES, "help corpus index");
        }
        if (body.length == 0) {
            throw new IOException("help corpus index is empty");
        }
        var index = MAPPER.readValue(body, CorpusIndex.class);
        if (index == null) {
            throw new IOException("help corpus index is the JSON literal null");
        }
        return index.validated();
    }

    /**
     * The verified archive on disk, downloading it only when the cache does not already hold it.
     * A cached file is re-hashed on every pass, exactly as {@code DriverJarCache} re-verifies a
     * cached JAR: the pin is worth nothing if it is only checked the first time.
     */
    private Path ensureCachedArchive(CorpusIndex index) throws IOException, InterruptedException {
        var target = properties.cacheDir().resolve("help-corpus-" + index.corpusVersion() + ".tar.gz");
        if (Files.isRegularFile(target)) {
            if (Files.size(target) <= MAX_ARCHIVE_BYTES && sha256(target).equals(index.sha256())) {
                log.debug("Reusing the cached help corpus archive at {}", target);
                return target;
            }
            log.warn("Cached help corpus archive at {} no longer matches its pinned checksum; "
                    + "re-downloading", target);
            Files.deleteIfExists(target);
        }
        Files.createDirectories(properties.cacheDir());
        download(index, target);
        return target;
    }

    private void download(CorpusIndex index, Path target) throws IOException, InterruptedException {
        // The cache directory can be shared (the Helm chart points it at the driver-cache volume) and
        // every replica refreshes at startup at once, so the scratch name carries this process's id.
        // A fixed name would let two replicas interleave writes and report the result as a checksum
        // mismatch, which reads like tampering rather than the write race it is.
        var part = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid()
                + ".part");
        try {
            var response = httpClient().send(get(index.url()),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("help corpus archive answered HTTP "
                            + response.statusCode() + " from " + index.url());
                }
                copyCapped(in, part, MAX_ARCHIVE_BYTES);
            }
            var actual = sha256(part);
            if (!actual.equals(index.sha256())) {
                throw new IOException("help corpus archive from " + index.url() + " hashes to "
                        + actual + ", not the pinned " + index.sha256());
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteQuietly(part);
            throw e;
        }
    }

    private HttpRequest get(String url) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("'" + url + "' is not a URL", e);
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // getHost() is null for a URI that parses but has no usable authority — "https:///x",
        // "https://host:notaport/x". HttpRequest.newBuilder throws IllegalArgumentException on those,
        // so they are rejected here, where the failure is a described one.
        if (!uri.isAbsolute() || uri.getHost() == null || !isAllowedScheme(scheme, uri.getHost())) {
            throw new IOException("refusing to fetch a help corpus over '" + url + "'");
        }
        try {
            return HttpRequest.newBuilder(uri).timeout(READ_TIMEOUT).GET().build();
        } catch (IllegalArgumentException e) {
            throw new IOException("'" + url + "' is not a fetchable URL", e);
        }
    }

    /**
     * HTTPS anywhere; plain HTTP only against loopback. Over plaintext the pinned digest arrives on
     * the same channel as the artifact it pins, so an on-path attacker replaces both and the
     * verification proves nothing — and what lands is text fed straight into the help agent's prompt.
     * Loopback stays allowed because it cannot be intercepted, and the tests serve from it.
     */
    private static boolean isAllowedScheme(String scheme, String host) {
        if ("https".equals(scheme)) {
            return true;
        }
        return "http".equals(scheme) && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads at most {@code max + 1} bytes, so an endless body is refused after one byte over the
     * limit rather than buffered in full and measured afterwards.
     */
    static byte[] readCapped(InputStream in, int max, String what) throws IOException {
        var bytes = in.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new IOException(what + " exceeds the " + max + "-byte limit");
        }
        return bytes;
    }

    /** The same cap, applied while streaming to disk so an oversized archive is never fully written. */
    static void copyCapped(InputStream in, Path part, long max) throws IOException {
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new IOException("help corpus archive exceeds the " + max + "-byte limit");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort: a leftover .part is overwritten by the next attempt
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Wire shape of {@code help-corpus-index.json}, published by the release workflow and moved by GA
     * releases only.
     *
     * @param version       the AccessFlow release that published the bundle, for logs
     * @param corpusVersion the bundle's content-derived corpus version
     * @param url           where the archive lives
     * @param sha256        the archive's pinned digest — the only thing that makes this safe
     */
    record CorpusIndex(String version, String corpusVersion, String url, String sha256) {

        /** Every field is attacker-controlled until this passes; {@code corpusVersion} names a file. */
        CorpusIndex validated() throws IOException {
            require(version != null && !version.isBlank(), "version");
            require(corpusVersion != null && CORPUS_VERSION.matcher(corpusVersion).matches(),
                    "corpusVersion");
            require(url != null && !url.isBlank(), "url");
            require(sha256 != null && SHA256.matcher(sha256).matches(), "sha256");
            return this;
        }

        private static void require(boolean condition, String field) throws IOException {
            if (!condition) {
                throw new IOException("help corpus index has no usable '" + field + "'");
            }
        }
    }
}
