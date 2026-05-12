package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceTypeResponseTest {

    @Test
    void mapsAllFieldsFromDriverTypeInfo() {
        var info = new DriverTypeInfo(DbType.POSTGRESQL, "PostgreSQL", "/db-icons/postgresql.svg",
                5432, SslMode.VERIFY_FULL,
                "jdbc:postgresql://{host}:{port}/{database_name}", DriverStatus.READY, true);

        var response = DatasourceTypeResponse.from(info);

        assertThat(response.code()).isEqualTo(DbType.POSTGRESQL);
        assertThat(response.displayName()).isEqualTo("PostgreSQL");
        assertThat(response.iconUrl()).isEqualTo("/db-icons/postgresql.svg");
        assertThat(response.defaultPort()).isEqualTo(5432);
        assertThat(response.defaultSslMode()).isEqualTo(SslMode.VERIFY_FULL);
        assertThat(response.jdbcUrlTemplate())
                .isEqualTo("jdbc:postgresql://{host}:{port}/{database_name}");
        assertThat(response.driverStatus()).isEqualTo(DriverStatus.READY);
        assertThat(response.bundled()).isTrue();
    }

    @Test
    void mapsBundledFalseForExternalDrivers() {
        var info = new DriverTypeInfo(DbType.MYSQL, "MySQL", "/db-icons/mysql.svg", 3306,
                SslMode.REQUIRE, "jdbc:mysql://{host}:{port}/{database_name}",
                DriverStatus.AVAILABLE, false);

        var response = DatasourceTypeResponse.from(info);

        assertThat(response.bundled()).isFalse();
        assertThat(response.driverStatus()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void typesResponseWrapsListInCanonicalOrder() {
        var infoA = new DriverTypeInfo(DbType.MYSQL, "MySQL", "/db-icons/mysql.svg", 3306,
                SslMode.REQUIRE, "jdbc:mysql://{host}:{port}/{database_name}",
                DriverStatus.AVAILABLE, false);
        var infoB = new DriverTypeInfo(DbType.MARIADB, "MariaDB", "/db-icons/mariadb.svg", 3306,
                SslMode.REQUIRE, "jdbc:mariadb://{host}:{port}/{database_name}",
                DriverStatus.UNAVAILABLE, false);

        var response = DatasourceTypesResponse.from(List.of(infoA, infoB));

        assertThat(response.types()).hasSize(2);
        assertThat(response.types().get(0).code()).isEqualTo(DbType.MYSQL);
        assertThat(response.types().get(1).code()).isEqualTo(DbType.MARIADB);
    }
}
