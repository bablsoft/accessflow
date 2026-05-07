package com.partqam.accessflow.proxy.internal.driver;

import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;

import java.util.EnumMap;
import java.util.Map;

/**
 * Static, in-process allowlist mapping {@link DbType} to JDBC driver coordinates.
 * The PostgreSQL entry is bundled (resolved against the application classloader);
 * every other entry is downloaded on demand and verified via SHA-256.
 */
final class DriverRegistry {

    private static final Map<DbType, DriverRegistryEntry> ENTRIES = buildEntries();

    private DriverRegistry() {
    }

    static DriverRegistryEntry require(DbType dbType) {
        var entry = ENTRIES.get(dbType);
        if (entry == null) {
            throw new IllegalStateException("No driver registry entry for " + dbType);
        }
        return entry;
    }

    static Map<DbType, DriverRegistryEntry> entries() {
        return ENTRIES;
    }

    private static Map<DbType, DriverRegistryEntry> buildEntries() {
        Map<DbType, DriverRegistryEntry> map = new EnumMap<>(DbType.class);
        map.put(DbType.POSTGRESQL, new DriverRegistryEntry(
                DbType.POSTGRESQL,
                "PostgreSQL",
                "/db-icons/postgresql.svg",
                5432,
                SslMode.VERIFY_FULL,
                "jdbc:postgresql://{host}:{port}/{database_name}",
                "org.postgresql",
                "postgresql",
                "bundled",
                "",
                "org.postgresql.Driver",
                true));
        map.put(DbType.MYSQL, new DriverRegistryEntry(
                DbType.MYSQL,
                "MySQL",
                "/db-icons/mysql.svg",
                3306,
                SslMode.REQUIRE,
                "jdbc:mysql://{host}:{port}/{database_name}",
                "com.mysql",
                "mysql-connector-j",
                "9.7.0",
                "0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44",
                "com.mysql.cj.jdbc.Driver",
                false));
        map.put(DbType.MARIADB, new DriverRegistryEntry(
                DbType.MARIADB,
                "MariaDB",
                "/db-icons/mariadb.svg",
                3306,
                SslMode.REQUIRE,
                "jdbc:mariadb://{host}:{port}/{database_name}",
                "org.mariadb.jdbc",
                "mariadb-java-client",
                "3.5.3",
                "85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706",
                "org.mariadb.jdbc.Driver",
                false));
        map.put(DbType.ORACLE, new DriverRegistryEntry(
                DbType.ORACLE,
                "Oracle Database",
                "/db-icons/generic.svg",
                1521,
                SslMode.REQUIRE,
                "jdbc:oracle:thin:@//{host}:{port}/{database_name}",
                "com.oracle.database.jdbc",
                "ojdbc11",
                "23.8.0.25.04",
                "756663c916f64e94659c197768f5c7a29706bc0fd4274dfdf88f474885516d06",
                "oracle.jdbc.OracleDriver",
                false));
        map.put(DbType.MSSQL, new DriverRegistryEntry(
                DbType.MSSQL,
                "Microsoft SQL Server",
                "/db-icons/generic.svg",
                1433,
                SslMode.REQUIRE,
                "jdbc:sqlserver://{host}:{port};databaseName={database_name}",
                "com.microsoft.sqlserver",
                "mssql-jdbc",
                "12.10.0.jre11",
                "8b80e2a3d254c26f66d479bc51d2d235f054eeb6e8394260c129bbd7fc7394a7",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                false));
        return Map.copyOf(map);
    }
}
