package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.internal.codec.SyslogCefSinkConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyslogTcpClientTest {

    private final SyslogTcpClient client = new SyslogTcpClient();

    @Test
    void framesMessagesWithByteAccurateOctetCounting() throws Exception {
        try (var serverSocket = new ServerSocket(0)) {
            var received = CompletableFuture.supplyAsync(() -> readAll(serverSocket));

            // The umlaut makes the byte length differ from the char length.
            var first = "<109>1 hello wörld";
            var second = "<108>1 second";
            client.send(new SyslogCefSinkConfig("127.0.0.1", serverSocket.getLocalPort(), false),
                    List.of(first, second));

            var bytes = received.get(10, TimeUnit.SECONDS);
            var firstBytes = first.getBytes(StandardCharsets.UTF_8);
            var secondBytes = second.getBytes(StandardCharsets.UTF_8);
            assertThat(firstBytes.length).isNotEqualTo(first.length());

            var expected = new ByteArrayOutputStream();
            expected.writeBytes(String.valueOf(firstBytes.length)
                    .getBytes(StandardCharsets.US_ASCII));
            expected.write(' ');
            expected.writeBytes(firstBytes);
            expected.writeBytes(String.valueOf(secondBytes.length)
                    .getBytes(StandardCharsets.US_ASCII));
            expected.write(' ');
            expected.writeBytes(secondBytes);
            assertThat(bytes).isEqualTo(expected.toByteArray());
        }
    }

    @Test
    void connectionRefusedThrowsDeliveryException() throws Exception {
        int freePort;
        try (var socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        var config = new SyslogCefSinkConfig("127.0.0.1", freePort, false);
        assertThatThrownBy(() -> client.send(config, List.of("<109>1 message")))
                .isInstanceOf(AuditSinkDeliveryException.class)
                .hasMessageContaining("127.0.0.1:" + freePort);
    }

    private static byte[] readAll(ServerSocket serverSocket) {
        try (var connection = serverSocket.accept()) {
            return connection.getInputStream().readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
