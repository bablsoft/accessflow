package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.couchbase.client.core.env.SecurityConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CouchbaseConnectionStringFactoryTest {

    private final CouchbaseConnectionStringFactory factory = new CouchbaseConnectionStringFactory();

    private static DatasourceConnectionDescriptor descriptor(String host, Integer port,
                                                             SslMode sslMode, String override) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.COUCHBASE, host, port, "bucket1", "admin", "enc", sslMode, 10, 1000, true,
                null, false, null, "couchbase", override, null, null, null, true);
    }

    @Test
    void disableUsesPlainScheme() {
        var spec = factory.build(descriptor("db.example", 11210, SslMode.DISABLE, null));
        assertThat(spec.connectionString()).isEqualTo("couchbase://db.example:11210");
        assertThat(securityOf(spec).tlsEnabled()).isFalse();
    }

    @Test
    void requireUsesTlsSchemeTrustingAnyCertificate() {
        var spec = factory.build(descriptor("db.example", 11207, SslMode.REQUIRE, null));
        assertThat(spec.connectionString()).isEqualTo("couchbases://db.example:11207");
        var security = securityOf(spec);
        assertThat(security.trustManagerFactory()).isNotNull();
        assertThat(security.hostnameVerificationEnabled()).isFalse();
    }

    @Test
    void verifyCaKeepsDefaultTrustWithoutHostnameVerification() {
        var spec = factory.build(descriptor("db.example", null, SslMode.VERIFY_CA, null));
        assertThat(spec.connectionString()).isEqualTo("couchbases://db.example");
        var security = securityOf(spec);
        assertThat(security.trustManagerFactory()).isNull();
        assertThat(security.hostnameVerificationEnabled()).isFalse();
    }

    @Test
    void verifyFullLeavesSdkDefaults() {
        var spec = factory.build(descriptor("db.example", 11207, SslMode.VERIFY_FULL, null));
        assertThat(spec.connectionString()).isEqualTo("couchbases://db.example:11207");
        var security = securityOf(spec);
        assertThat(security.trustManagerFactory()).isNull();
        assertThat(security.hostnameVerificationEnabled()).isTrue();
    }

    @Test
    void urlOverrideIsTakenVerbatim() {
        var spec = factory.build(descriptor("ignored", 1, SslMode.VERIFY_FULL,
                "  couchbases://node1,node2?network=external  "));
        assertThat(spec.connectionString()).isEqualTo("couchbases://node1,node2?network=external");
        assertThat(securityOf(spec).hostnameVerificationEnabled()).isTrue();
    }

    @Test
    void nullHostFallsBackToLocalhost() {
        var spec = factory.build(descriptor(null, null, SslMode.DISABLE, null));
        assertThat(spec.connectionString()).isEqualTo("couchbase://localhost");
    }

    /** Apply the spec's customizer to a fresh builder and read the resulting config back. */
    private static SecurityConfig securityOf(CouchbaseConnectionStringFactory.ConnectionSpec spec) {
        var builder = SecurityConfig.builder();
        spec.security().accept(builder);
        return builder.build();
    }
}
