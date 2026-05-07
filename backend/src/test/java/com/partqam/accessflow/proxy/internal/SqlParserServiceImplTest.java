package com.partqam.accessflow.proxy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.proxy.api.InvalidSqlException;
import com.partqam.accessflow.proxy.api.SqlParseResult;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;

class SqlParserServiceImplTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private SqlParserServiceImpl service;

    @BeforeEach
    void setUp() {
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return switch (key) {
                case "error.sql_empty" -> "SQL must not be empty";
                case "error.sql_parse_failed" -> "Failed to parse SQL";
                case "error.sql_no_statement" -> "SQL must contain a statement";
                case "error.sql_multiple_statements" -> "Multiple SQL statements are not allowed";
                default -> key;
            };
        });
        service = new SqlParserServiceImpl(messageSource);
    }

    @Test
    void parsesSimpleSelect() {
        SqlParseResult result = service.parse("SELECT * FROM users");

        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.statement()).isInstanceOf(Select.class);
    }

    @Test
    void parsesSelectWithCte() {
        SqlParseResult result =
                service.parse("WITH t AS (SELECT 1 AS x) SELECT x FROM t");

        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.statement()).isInstanceOf(Select.class);
    }

    @Test
    void parsesSelectWithJoin() {
        SqlParseResult result = service.parse(
                "SELECT u.id, o.id FROM users u JOIN orders o ON o.user_id = u.id");

        assertThat(result.type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void parsesInsert() {
        SqlParseResult result =
                service.parse("INSERT INTO users (id, email) VALUES (1, 'a@b.c')");

        assertThat(result.type()).isEqualTo(QueryType.INSERT);
        assertThat(result.statement()).isInstanceOf(Insert.class);
    }

    @Test
    void parsesUpdate() {
        SqlParseResult result =
                service.parse("UPDATE users SET email = 'x@y.z' WHERE id = 1");

        assertThat(result.type()).isEqualTo(QueryType.UPDATE);
        assertThat(result.statement()).isInstanceOf(Update.class);
    }

    @Test
    void parsesDelete() {
        SqlParseResult result = service.parse("DELETE FROM users WHERE id = 1");

        assertThat(result.type()).isEqualTo(QueryType.DELETE);
        assertThat(result.statement()).isInstanceOf(Delete.class);
    }

    @Test
    void parsesCreateTableAsDdl() {
        SqlParseResult result = service.parse("CREATE TABLE t (id INT)");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
        assertThat(result.statement()).isInstanceOf(CreateTable.class);
    }

    @Test
    void parsesAlterTableAsDdl() {
        SqlParseResult result =
                service.parse("ALTER TABLE users ADD COLUMN nickname VARCHAR(50)");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void parsesDropTableAsDdl() {
        SqlParseResult result = service.parse("DROP TABLE users");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
        assertThat(result.statement()).isInstanceOf(Drop.class);
    }

    @Test
    void parsesTruncateAsDdl() {
        SqlParseResult result = service.parse("TRUNCATE TABLE users");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void parsesCreateIndexAsDdl() {
        SqlParseResult result =
                service.parse("CREATE INDEX idx_users_email ON users (email)");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void parsesCreateViewAsDdl() {
        SqlParseResult result = service.parse(
                "CREATE VIEW active_users AS SELECT id FROM users WHERE active = TRUE");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void mergeStatementClassifiedAsOther() {
        SqlParseResult result = service.parse(
                "MERGE INTO users u USING staging s ON u.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET u.email = s.email");

        assertThat(result.type()).isEqualTo(QueryType.OTHER);
    }

    @Test
    void rejectsInvalidSyntax() {
        assertThatThrownBy(() -> service.parse("SELEKT * FROM users"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("Failed to parse SQL");
    }

    @Test
    void rejectsEmptyString() {
        assertThatThrownBy(() -> service.parse(""))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void rejectsWhitespaceOnly() {
        assertThatThrownBy(() -> service.parse("   \n\t "))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> service.parse(null))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsStackedStatements() {
        assertThatThrownBy(() -> service.parse("SELECT 1; DROP TABLE users;"))
                .isInstanceOf(InvalidSqlException.class);
    }
}
