package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end over a real in-process TCP server: one framed CEF message arrives per event. */
class SyslogCefSinkDelivererTest {

    private final AuditSinkConfigCodec codec = new AuditSinkConfigCodec(
            JsonMapper.builder().build(), new NoopEncryption());
    private final SyslogCefSinkDeliverer deliverer =
            new SyslogCefSinkDeliverer(codec, new CefFormatter(), new SyslogTcpClient());

    @Test
    void typeIsSyslogCef() {
        assertThat(deliverer.type()).isEqualTo(AuditSinkType.SYSLOG_CEF);
    }

    @Test
    void deliversOneFramedCefMessagePerEvent() throws Exception {
        try (var serverSocket = new ServerSocket(0)) {
            var received = CompletableFuture.supplyAsync(() -> readAll(serverSocket));

            var sink = new AuditSinkEntity();
            sink.setId(UUID.randomUUID());
            sink.setOrganizationId(UUID.randomUUID());
            sink.setName("syslog");
            sink.setType(AuditSinkType.SYSLOG_CEF);
            sink.setConfigJson(codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                    "host", "127.0.0.1",
                    "port", serverSocket.getLocalPort(),
                    "protocol", "TCP")));

            deliverer.deliver(sink, List.of(event("QUERY_SUBMITTED"), event("QUERY_REJECTED")));

            var messages = deframe(received.get(10, TimeUnit.SECONDS));
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0))
                    .startsWith("<109>1 ")
                    .contains("CEF:0|AccessFlow|AccessFlow|1.0|QUERY_SUBMITTED|QUERY_SUBMITTED|5|");
            assertThat(messages.get(1))
                    .startsWith("<108>1 ")
                    .contains("CEF:0|AccessFlow|AccessFlow|1.0|QUERY_REJECTED|QUERY_REJECTED|7|");
        }
    }

    private static AuditExportEvent event(String action) {
        return new AuditExportEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                action, "query_request", UUID.randomUUID(), "{}", "203.0.113.5", "ua/1",
                Instant.parse("2026-08-19T10:15:30.123456Z"), "aa", "bb");
    }

    /** Parses RFC 6587 octet-counting frames ({@code <length> <message>}) back into messages. */
    private static List<String> deframe(byte[] bytes) {
        var messages = new ArrayList<String>();
        int i = 0;
        while (i < bytes.length) {
            int space = i;
            while (bytes[space] != ' ') {
                space++;
            }
            int length = Integer.parseInt(
                    new String(bytes, i, space - i, StandardCharsets.US_ASCII));
            messages.add(new String(bytes, space + 1, length, StandardCharsets.UTF_8));
            i = space + 1 + length;
        }
        return messages;
    }

    private static byte[] readAll(ServerSocket serverSocket) {
        try (var connection = serverSocket.accept()) {
            return connection.getInputStream().readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class NoopEncryption implements CredentialEncryptionService {
        @Override
        public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return ciphertext;
        }
    }
}
