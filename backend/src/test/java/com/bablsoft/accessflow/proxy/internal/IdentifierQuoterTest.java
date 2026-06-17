package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierQuoterTest {

    @Test
    void postgresUsesAnsiDoubleQuotes() {
        assertThat(IdentifierQuoter.quote(DbType.POSTGRESQL, "users")).isEqualTo("\"users\"");
        assertThat(IdentifierQuoter.qualifiedTable(DbType.POSTGRESQL, "public", "users"))
                .isEqualTo("\"public\".\"users\"");
    }

    @Test
    void oracleAndCustomAlsoUseAnsiDoubleQuotes() {
        assertThat(IdentifierQuoter.quote(DbType.ORACLE, "T")).isEqualTo("\"T\"");
        assertThat(IdentifierQuoter.quote(DbType.CUSTOM, "t")).isEqualTo("\"t\"");
    }

    @Test
    void mysqlAndMariadbUseBackticks() {
        assertThat(IdentifierQuoter.quote(DbType.MYSQL, "orders")).isEqualTo("`orders`");
        assertThat(IdentifierQuoter.qualifiedTable(DbType.MARIADB, "shop", "orders"))
                .isEqualTo("`shop`.`orders`");
    }

    @Test
    void mssqlUsesBrackets() {
        assertThat(IdentifierQuoter.quote(DbType.MSSQL, "users")).isEqualTo("[users]");
        assertThat(IdentifierQuoter.qualifiedTable(DbType.MSSQL, "dbo", "users"))
                .isEqualTo("[dbo].[users]");
    }

    @Test
    void embeddedQuoteCharactersAreDoubledPerDialect() {
        assertThat(IdentifierQuoter.quote(DbType.POSTGRESQL, "we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThat(IdentifierQuoter.quote(DbType.MYSQL, "we`ird")).isEqualTo("`we``ird`");
        assertThat(IdentifierQuoter.quote(DbType.MSSQL, "we]ird")).isEqualTo("[we]]ird]");
    }

    @Test
    void blankSchemaYieldsBareTable() {
        assertThat(IdentifierQuoter.qualifiedTable(DbType.POSTGRESQL, null, "users"))
                .isEqualTo("\"users\"");
        assertThat(IdentifierQuoter.qualifiedTable(DbType.POSTGRESQL, "  ", "users"))
                .isEqualTo("\"users\"");
    }
}
