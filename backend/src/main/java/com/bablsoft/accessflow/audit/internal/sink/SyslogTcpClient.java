package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.internal.codec.SyslogCefSinkConfig;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sends syslog messages over one TCP (or TLS) connection per batch, framed with RFC 6587
 * octet-counting ({@code <length> <message>}). TLS validates the server certificate against the
 * system truststore <em>with hostname verification enabled</em> — a compliance pipe must not be
 * silently MITM-able, so there is deliberately no skip-verify option.
 */
@Component
public class SyslogTcpClient {

    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int READ_TIMEOUT_MILLIS = 10_000;

    public void send(SyslogCefSinkConfig config, List<String> messages) {
        try (var socket = open(config)) {
            OutputStream out = socket.getOutputStream();
            for (String message : messages) {
                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                out.write(String.valueOf(payload.length).getBytes(StandardCharsets.US_ASCII));
                out.write(' ');
                out.write(payload);
            }
            out.flush();
        } catch (IOException ex) {
            throw new AuditSinkDeliveryException(
                    "Syslog delivery to " + config.host() + ":" + config.port()
                            + " failed: " + ex.getMessage(), ex);
        }
    }

    private Socket open(SyslogCefSinkConfig config) throws IOException {
        if (!config.tls()) {
            var socket = new Socket();
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(config.host(), config.port()),
                    CONNECT_TIMEOUT_MILLIS);
            return socket;
        }
        var plain = new Socket();
        plain.setSoTimeout(READ_TIMEOUT_MILLIS);
        plain.connect(new InetSocketAddress(config.host(), config.port()),
                CONNECT_TIMEOUT_MILLIS);
        var factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        var ssl = (SSLSocket) factory.createSocket(plain, config.host(), config.port(), true);
        var params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }
}
