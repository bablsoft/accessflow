package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.proxy.internal.driver.ConnectorManifest.DriverArtifact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorManifestTest {

    private static final String REPO = "https://repo1.maven.org/maven2";

    @Test
    void mavenArtifactJarFileNameAndSourceUrl() {
        var driver = new DriverArtifact("maven", "com.mysql", "mysql-connector-j",
                "9.7.0", null, null, null, "a".repeat(64));

        assertThat(driver.jarFileName()).isEqualTo("mysql-connector-j-9.7.0.jar");
        assertThat(driver.sourceUrl(REPO))
                .isEqualTo(REPO + "/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar");
    }

    @Test
    void mavenArtifactWithClassifierAddsItToJarName() {
        var driver = new DriverArtifact("maven", "com.clickhouse", "clickhouse-jdbc",
                "0.9.0", "all", null, null, "b".repeat(64));

        assertThat(driver.jarFileName()).isEqualTo("clickhouse-jdbc-0.9.0-all.jar");
        assertThat(driver.sourceUrl(REPO)).endsWith("/clickhouse-jdbc-0.9.0-all.jar");
    }

    @Test
    void urlArtifactUsesLiteralUrlAndFileName() {
        var driver = new DriverArtifact("url", null, null, null, null,
                "https://example.com/foo-1.0-all.jar", "foo-1.0-all.jar", "c".repeat(64));

        assertThat(driver.jarFileName()).isEqualTo("foo-1.0-all.jar");
        // URL artifacts ignore the configured repository base.
        assertThat(driver.sourceUrl(REPO)).isEqualTo("https://example.com/foo-1.0-all.jar");
    }

    @Test
    void bundledManifestExposesIconAndNullDriverFields() {
        var manifest = new ConnectorManifest(1, "postgresql", "PostgreSQL", DbType.POSTGRESQL,
                "PGDG", "desc", "https://jdbc.postgresql.org/", "logo.svg", 5432,
                SslMode.VERIFY_FULL, "jdbc:postgresql://{host}:{port}/{database_name}",
                "org.postgresql.Driver", true, null);

        assertThat(manifest.iconUrl()).isEqualTo("/db-icons/postgresql.svg");
        assertThat(manifest.jarFileName()).isNull();
        assertThat(manifest.sourceUrl(REPO)).isNull();
        assertThat(manifest.sha256()).isNull();
    }

    @Test
    void externalManifestDelegatesToDriverArtifact() {
        var driver = new DriverArtifact("maven", "com.mysql", "mysql-connector-j",
                "9.7.0", null, null, null, "d".repeat(64));
        var manifest = new ConnectorManifest(1, "mysql", "MySQL", DbType.MYSQL, "Oracle", null,
                null, "logo.svg", 3306, SslMode.REQUIRE,
                "jdbc:mysql://{host}:{port}/{database_name}", "com.mysql.cj.jdbc.Driver",
                false, driver);

        assertThat(manifest.jarFileName()).isEqualTo("mysql-connector-j-9.7.0.jar");
        assertThat(manifest.sha256()).isEqualTo("d".repeat(64));
        assertThat(manifest.sourceUrl(REPO)).contains("/com/mysql/");
    }
}
