package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpCorpusProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract here is asymmetric, and that is the point: a successful refresh may change which
 * corpus this process answers from, and <strong>every</strong> other outcome must leave it exactly as
 * it was. So each failure case asserts the same two things — the active corpus version is still the
 * bundled one, and nothing was left behind in the cache directory.
 *
 * <p>Driven against a real loopback {@code HttpServer} rather than a mocked client, because three of
 * the behaviours under test — a non-2xx, an unreachable host, and a body that never ends — are
 * properties of the transport, and a stub would only ever confirm the stub.
 */
class HelpCorpusRemoteRefresherTest {

    private static final String INDEX_PATH = "/help-corpus/help-corpus-index.json";
    private static final String ARCHIVE_PATH = "/help-corpus/help-corpus-9.9.9.tar.gz";

    @TempDir Path cacheDir;

    private HttpServer server;
    private String baseUrl;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void activatesANewerPublishedCorpus() throws IOException {
        var bundle = bundledCorpus();
        var archive = TarGzFixtures.archiveOf("good");
        serveIndex("0d746008cf42", sha256(archive));
        serve(ARCHIVE_PATH, 200, archive);

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
        assertThat(bundle.chunkCount()).isEqualTo(2);
        assertThat(bundle.quickReference()).contains("The query lifecycle");
        // The bundled version is still reportable, so a log line can say what was replaced.
        assertThat(bundle.bundledCorpusVersion()).isNotEqualTo("0d746008cf42");
        assertThat(cacheDir.resolve("help-corpus-0d746008cf42.tar.gz")).exists();
    }

    @Test
    void doesNothingWhenThePublishedCorpusIsTheOneAlreadyActive() throws IOException {
        var bundle = bundledCorpus();
        serveIndex(bundle.corpusVersion(), sha256(new byte[0]));

        refresher(bundle).refresh();

        // The version compare is the whole point of the pointer file: an install that is already
        // current must not download an archive to discover that.
        assertThat(requested).containsExactly(INDEX_PATH);
        assertThat(bundle.corpusVersion()).isEqualTo(bundle.bundledCorpusVersion());
    }

    @Test
    void reusesAVerifiedArchiveAlreadyInTheCache() throws IOException {
        var bundle = bundledCorpus();
        var archive = TarGzFixtures.archiveOf("good");
        Files.write(cacheDir.resolve("help-corpus-0d746008cf42.tar.gz"), archive);
        serveIndex("0d746008cf42", sha256(archive));

        refresher(bundle).refresh();

        assertThat(requested).containsExactly(INDEX_PATH);
        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
    }

    @Test
    void reDownloadsACachedArchiveThatNoLongerMatchesItsPin() throws IOException {
        var bundle = bundledCorpus();
        var archive = TarGzFixtures.archiveOf("good");
        Files.write(cacheDir.resolve("help-corpus-0d746008cf42.tar.gz"), "tampered".getBytes(StandardCharsets.UTF_8));
        serveIndex("0d746008cf42", sha256(archive));
        serve(ARCHIVE_PATH, 200, archive);

        refresher(bundle).refresh();

        // The pin is worth nothing if it is only checked on the download that first wrote the file.
        assertThat(requested).containsExactly(INDEX_PATH, ARCHIVE_PATH);
        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
    }

    @Test
    void keepsTheBundledCorpusWhenTheArchiveFailsItsChecksum() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serveIndex("0d746008cf42", sha256("something else".getBytes(StandardCharsets.UTF_8)));
        serve(ARCHIVE_PATH, 200, TarGzFixtures.archiveOf("good"));

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void keepsTheBundledCorpusWhenTheIndexAnswersNon2xx() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serve(INDEX_PATH, 404, "not found".getBytes(StandardCharsets.UTF_8));

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void keepsTheBundledCorpusWhenTheArchiveAnswersNon2xx() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serveIndex("0d746008cf42", sha256(TarGzFixtures.archiveOf("good")));
        serve(ARCHIVE_PATH, 500, "boom".getBytes(StandardCharsets.UTF_8));

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void keepsTheBundledCorpusWhenTheHostIsUnreachable() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        // A port nothing is listening on: connection refused, not a slow answer.
        var properties = new HelpCorpusProperties(true,
                "http://127.0.0.1:1/help-corpus/help-corpus-index.json", cacheDir, false);

        new HelpCorpusRemoteRefresher(properties, bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void makesNoRequestAtAllWhenOffline() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serveIndex("0d746008cf42", sha256(TarGzFixtures.archiveOf("good")));

        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, baseUrl + INDEX_PATH, cacheDir, true), bundle)
                .refresh();

        assertThat(requested).isEmpty();
        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void makesNoRequestAtAllWhenRemoteRefreshIsOff() throws IOException {
        var bundle = bundledCorpus();
        serveIndex("0d746008cf42", sha256(TarGzFixtures.archiveOf("good")));

        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(false, baseUrl + INDEX_PATH, cacheDir, false), bundle)
                .refresh();

        assertThat(requested).isEmpty();
    }

    @Test
    void refusesACorpusFromANewerAccessFlow() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        var archive = TarGzFixtures.archiveOf("future-schema");
        serveIndex("0d746008cf42", sha256(archive));
        serve(ARCHIVE_PATH, 200, archive);

        refresher(bundle).refresh();

        // The archive downloaded and hashed correctly; it is the corpus inside it this build cannot
        // read, and reading it anyway would silently drop whatever fields the new schema added.
        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(bundle.available()).isTrue();
    }

    @Test
    void refusesAnIndexWithAnUnusableCorpusVersion() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        // corpusVersion names a file in the cache directory, so it is validated before it is used.
        serveIndex("../../etc/passwd", sha256(new byte[0]));

        refresher(bundle).refresh();

        assertThat(requested).containsExactly(INDEX_PATH);
        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void refusesAnIndexThatNamesANonHttpArchive() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serve(INDEX_PATH, 200, ("""
                {"version":"9.9.9","corpusVersion":"0d746008cf42",
                 "url":"file:///etc/passwd","sha256":"%s"}""".formatted(sha256(new byte[0])))
                .getBytes(StandardCharsets.UTF_8));

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void keepsTheBundledCorpusWhenTheConfiguredIndexUrlHasNoHost() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();

        // Parses as an absolute https URI, but has no authority — HttpRequest.newBuilder throws
        // IllegalArgumentException on it, which is a sibling of IllegalStateException and would
        // otherwise escape refresh() and take the caller's whole indexing pass down with it.
        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, "https:///help-corpus-index.json", cacheDir, false),
                bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void keepsTheBundledCorpusWhenThePublishedArchiveUrlHasNoHost() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serve(INDEX_PATH, 200, ("{\"version\":\"9.9.9\",\"corpusVersion\":\"0d746008cf42\","
                + "\"url\":\"https://host:notaport/a.tar.gz\",\"sha256\":\"" + sha256(new byte[0])
                + "\"}").getBytes(StandardCharsets.UTF_8));

        refresher(bundle).refresh();

        // The same class of URL, but supplied by the published index rather than by the operator.
        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void refusesPlainHttpToANonLoopbackHost() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();

        // Over plaintext the pinned digest travels the same channel as the artifact it pins, so an
        // on-path attacker swaps both and the verification proves nothing. Loopback — which every
        // other test in this class uses — cannot be intercepted, so it stays allowed.
        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, "http://mirror.internal/index.json", cacheDir, false),
                bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void cutsAnOversizedArchiveOffAtTheCapInsteadOfWritingItOut() {
        var part = cacheDir.resolve("oversized.tar.gz.part");

        assertThatThrownBy(() -> HelpCorpusRemoteRefresher.copyCapped(
                new CountingInputStream(64 * 1024), part, 4096))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds the 4096-byte limit");

        // At most one buffer past the cap reaches the disk, rather than the whole body arriving and
        // being measured afterwards.
        assertThat(part.toFile().length()).isLessThanOrEqualTo(4096L + 8192L);
    }

    @Test
    void stopsReadingAnEndlessIndexBodyRatherThanBufferingIt() throws IOException {
        // The cap has to sit on the stream: a converter that buffered first would already have the
        // whole body in memory by the time anything measured it.
        var counting = new CountingInputStream(HelpCorpusRemoteRefresher.MAX_INDEX_BYTES * 4L);

        assertThatThrownBy(() -> HelpCorpusRemoteRefresher.readCapped(counting,
                HelpCorpusRemoteRefresher.MAX_INDEX_BYTES, "help corpus index"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds the");

        assertThat(counting.read).isEqualTo(HelpCorpusRemoteRefresher.MAX_INDEX_BYTES + 1L);
    }

    @Test
    void acceptsABodyExactlyAtTheCap() throws IOException {
        var body = new byte[HelpCorpusRemoteRefresher.MAX_INDEX_BYTES];

        assertThat(HelpCorpusRemoteRefresher.readCapped(new ByteArrayInputStream(body),
                HelpCorpusRemoteRefresher.MAX_INDEX_BYTES, "help corpus index")).hasSameSizeAs(body);
    }

    @Test
    void keepsTheBundledCorpusWhenTheIndexIsNotJson() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serve(INDEX_PATH, 200, "<html>oops</html>".getBytes(StandardCharsets.UTF_8));

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void keepsTheBundledCorpusWhenTheArchiveIsNotAnArchive() throws IOException {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        var body = "<html>404</html>".getBytes(StandardCharsets.UTF_8);
        serveIndex("0d746008cf42", sha256(body));
        serve(ARCHIVE_PATH, 200, body);

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void activatesAnArchiveEvenWhenTheIndexMisnamesItsVersion() throws IOException {
        var bundle = bundledCorpus();
        var archive = TarGzFixtures.archiveOf("good");
        serveIndex("aaaaaaaaaaaa", sha256(archive));
        serve(ARCHIVE_PATH, 200, archive);

        refresher(bundle).refresh();

        // The archive hashed to the pinned value, so it is the artifact the publisher signed for and
        // its own manifest is authoritative; only the pointer's label was wrong. Discarding a
        // verified corpus over a mislabelled pointer would be the worse trade.
        assertThat(bundle.corpusVersion()).isEqualTo("0d746008cf42");
    }

    @Test
    void keepsTheBundledCorpusWhenTheIndexBodyIsEmpty() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serve(INDEX_PATH, 200, new byte[0]);

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void keepsTheBundledCorpusWhenTheIndexIsTheJsonLiteralNull() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();
        serve(INDEX_PATH, 200, "null".getBytes(StandardCharsets.UTF_8));

        refresher(bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void keepsTheBundledCorpusWhenTheIndexUrlIsNotAUrlAtAll() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();

        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, "ht tp://not a url", cacheDir, false), bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void keepsTheBundledCorpusWhenAnHttpsUrlHasAnImpossiblePort() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();

        // Passes every check in get() — absolute, https, a host — and then HttpRequest.newBuilder
        // still refuses it. That is the throw this class must convert rather than propagate.
        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, "https://mirror.internal:99999/index.json", cacheDir,
                        false), bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
    }

    @Test
    void reachesOutOverHttpsWithoutTheLoopbackException() {
        var bundle = bundledCorpus();
        var bundled = bundle.corpusVersion();

        // Nothing answers on that port, so this only proves https is permitted for a non-loopback
        // host — the case every real install uses and no other test here exercises.
        new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, "https://127.0.0.1:1/index.json", cacheDir, false),
                bundle).refresh();

        assertThat(bundle.corpusVersion()).isEqualTo(bundled);
        assertThat(cacheDirEntries()).isEmpty();
    }

    @Test
    void refusesAnIndexMissingAnyRequiredField() {
        var good = new HelpCorpusRemoteRefresher.CorpusIndex("1.2.3", "0d746008cf42",
                "https://example.test/a.tar.gz", "a".repeat(64));

        assertThatCode(good::validated).doesNotThrowAnyException();
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(null, good.corpusVersion(),
                good.url(), good.sha256()).validated()).hasMessageContaining("'version'");
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(" ", good.corpusVersion(),
                good.url(), good.sha256()).validated()).hasMessageContaining("'version'");
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(good.version(), null,
                good.url(), good.sha256()).validated()).hasMessageContaining("'corpusVersion'");
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(good.version(),
                good.corpusVersion(), null, good.sha256()).validated()).hasMessageContaining("'url'");
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(good.version(),
                good.corpusVersion(), " ", good.sha256()).validated()).hasMessageContaining("'url'");
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(good.version(),
                good.corpusVersion(), good.url(), null).validated()).hasMessageContaining("'sha256'");
        // Not hex, and the wrong length: the digest names a cache file and pins a download.
        assertThatThrownBy(() -> new HelpCorpusRemoteRefresher.CorpusIndex(good.version(),
                good.corpusVersion(), good.url(), "zz").validated())
                .hasMessageContaining("'sha256'");
    }

    /** Remote refresh switched on and online — the configuration every failure case starts from. */
    private HelpCorpusRemoteRefresher refresher(HelpCorpusBundle bundle) {
        return new HelpCorpusRemoteRefresher(
                new HelpCorpusProperties(true, baseUrl + INDEX_PATH, cacheDir, false), bundle);
    }

    /** The real classpath corpus, so an activated fixture is provably a different one. */
    private static HelpCorpusBundle bundledCorpus() {
        return new HelpCorpusBundle(new DefaultResourceLoader());
    }

    private void serveIndex(String corpusVersion, String sha256) {
        serve(INDEX_PATH, 200, ("""
                {"version":"9.9.9","corpusVersion":"%s","url":"%s","sha256":"%s"}"""
                .formatted(corpusVersion, baseUrl + ARCHIVE_PATH, sha256))
                .getBytes(StandardCharsets.UTF_8));
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, recordingHandler(status, body));
    }

    private HttpHandler recordingHandler(int status, byte[] body) {
        return exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            respond(exchange, status, body);
        };
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private List<Path> cacheDirEntries() {
        try (var entries = Files.list(cacheDir)) {
            return entries.toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An endless body that counts what was actually pulled off it. */
    private static final class CountingInputStream extends InputStream {

        private final long limit;
        private long read;

        private CountingInputStream(long limit) {
            this.limit = limit;
        }

        @Override
        public int read() {
            if (read >= limit) {
                return -1;
            }
            read++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (read >= limit) {
                return -1;
            }
            int count = (int) Math.min(length, limit - read);
            java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            read += count;
            return count;
        }
    }
}
