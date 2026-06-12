package com.bablsoft.accessflow.engine.elasticsearch;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

/**
 * The trust-all {@link SSLContext} shared by both transport flavours for {@link
 * com.bablsoft.accessflow.core.api.SslMode#REQUIRE} — encrypt the channel without verifying the
 * server certificate, parity with the JDBC engines' {@code trustServerCertificate=true}. Lives in
 * {@code javax.net.ssl}, so it is independent of which Apache HttpComponents version a transport
 * uses. VERIFY_CA / VERIFY_FULL fall through to the JVM default trust store (configured per
 * transport).
 */
final class EsTls {

    private EsTls() {
    }

    static SSLContext trustAllContext() {
        try {
            var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{TRUST_ALL}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize TLS for search connection", e);
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Intentionally permissive: REQUIRE encrypts without certificate verification.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Intentionally permissive: REQUIRE encrypts without certificate verification.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
