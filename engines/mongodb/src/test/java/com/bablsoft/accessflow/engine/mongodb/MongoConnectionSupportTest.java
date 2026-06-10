package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MongoConnectionSupportTest {

    @Test
    void databaseNamePrefersDescriptorField() {
        assertThat(MongoConnectionSupport.databaseName(descriptor("appdb", null))).isEqualTo("appdb");
    }

    @Test
    void databaseNameParsesOverrideUriWhenFieldBlank() {
        assertThat(MongoConnectionSupport.databaseName(
                descriptor("  ", "mongodb://h:27017/from_uri?tls=false"))).isEqualTo("from_uri");
    }

    @Test
    void databaseNameFallsBackToAdminWhenNothingResolvable() {
        assertThat(MongoConnectionSupport.databaseName(descriptor(null, null))).isEqualTo("admin");
        assertThat(MongoConnectionSupport.databaseName(
                descriptor(null, "mongodb://h:27017"))).isEqualTo("admin");
    }

    private static DatasourceConnectionDescriptor descriptor(String database, String override) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.MONGODB, "h", 27017, database, "u", "enc", SslMode.DISABLE, 10, 1000,
                true, null, false, null, null, override, null, null, null, true);
    }
}
