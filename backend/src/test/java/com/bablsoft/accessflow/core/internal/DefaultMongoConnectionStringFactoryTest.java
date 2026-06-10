package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MongoConnectionStringFactory.MongoClientOptions;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMongoConnectionStringFactoryTest {

    private final DefaultMongoConnectionStringFactory factory = new DefaultMongoConnectionStringFactory();
    private final MongoClientOptions options = MongoClientOptions.defaults();

    @Test
    void buildsFieldBasedUriWithCredentialsAndAuthSource() {
        var uri = factory.build(descriptor("mongo.host", 27017, "appdb", "svc", SslMode.REQUIRE,
                null), "p@ss/word", options);
        assertThat(uri).startsWith("mongodb://svc:p%40ss%2Fword@mongo.host:27017/appdb?");
        assertThat(uri).contains("authSource=admin");
        assertThat(uri).contains("tls=true").contains("tlsAllowInvalidCertificates=true");
        assertThat(uri).contains("connectTimeoutMS=10000");
        assertThat(uri).contains("maxPoolSize=10");
    }

    @Test
    void omitsCredentialsAndAuthSourceWhenNoUsername() {
        var uri = factory.build(descriptor("h", 27017, "db", null, SslMode.DISABLE, null), "", options);
        assertThat(uri).startsWith("mongodb://h:27017/db?");
        assertThat(uri).doesNotContain("@");
        assertThat(uri).doesNotContain("authSource");
        assertThat(uri).contains("tls=false");
    }

    @Test
    void verifyModesEnableTlsWithoutAllowingInvalidCerts() {
        var uri = factory.build(descriptor("h", 27017, "db", "u", SslMode.VERIFY_FULL, null), "pw",
                options);
        assertThat(uri).contains("tls=true");
        assertThat(uri).doesNotContain("tlsAllowInvalidCertificates");
    }

    @Test
    void overrideIsReturnedVerbatim() {
        var override = "mongodb+srv://u:p@cluster.example.net/db?retryWrites=true";
        var uri = factory.build(descriptor("ignored", 1, "ignored", "ignored", SslMode.DISABLE,
                override), "ignored", options);
        assertThat(uri).isEqualTo(override);
    }

    @Test
    void defaultsHostAndPortWhenMissing() {
        var uri = factory.build(descriptor(null, null, "db", null, SslMode.DISABLE, null), "", options);
        assertThat(uri).startsWith("mongodb://localhost:27017/db?");
    }

    private static DatasourceConnectionDescriptor descriptor(String host, Integer port, String db,
                                                             String user, SslMode ssl,
                                                             String override) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.MONGODB, host, port, db, user, "enc", ssl, 10, 1000, true, null, false,
                null, null, override, null, null, null, true);
    }
}
