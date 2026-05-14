package com.bablsoft.accessflow.proxy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.proxy.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.SqlParseResult;
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
                case "error.transaction_mixed_select_dml" -> "Transactions cannot mix SELECT with DML";
                case "error.transaction_select_only" -> "SELECT does not require a transaction";
                case "error.transaction_ddl_not_allowed" -> "DDL not allowed inside a transaction";
                case "error.transaction_other_not_allowed" -> "Only INSERT/UPDATE/DELETE allowed inside a transaction";
                case "error.transaction_nested_not_allowed" -> "Nested transactions are not allowed";
                case "error.transaction_rollback_not_allowed" -> "ROLLBACK is not allowed";
                case "error.transaction_savepoint_not_allowed" -> "SAVEPOINT is not allowed";
                case "error.transaction_unmatched_begin" -> "Missing closing COMMIT";
                case "error.transaction_unmatched_commit" -> "COMMIT without matching BEGIN";
                case "error.transaction_empty_body" -> "Empty transaction body";
                default -> key;
            };
        });
        service = new SqlParserServiceImpl(messageSource);
    }

    @Test
    void parsesSimpleSelect() {
        SqlParseResult result = service.parse("SELECT * FROM users");

        assertThat(result.type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void parsesSelectWithCte() {
        SqlParseResult result =
                service.parse("WITH t AS (SELECT 1 AS x) SELECT x FROM t");

        assertThat(result.type()).isEqualTo(QueryType.SELECT);
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
    }

    @Test
    void parsesUpdate() {
        SqlParseResult result =
                service.parse("UPDATE users SET email = 'x@y.z' WHERE id = 1");

        assertThat(result.type()).isEqualTo(QueryType.UPDATE);
    }

    @Test
    void parsesDelete() {
        SqlParseResult result = service.parse("DELETE FROM users WHERE id = 1");

        assertThat(result.type()).isEqualTo(QueryType.DELETE);
    }

    @Test
    void parsesCreateTableAsDdl() {
        SqlParseResult result = service.parse("CREATE TABLE t (id INT)");

        assertThat(result.type()).isEqualTo(QueryType.DDL);
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
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("Multiple SQL statements");
    }

    // ── transactional submissions ────────────────────────────────────────────

    @Test
    void parsesDmlTransactionWithBeginCommit() {
        SqlParseResult result = service.parse("""
                BEGIN;
                INSERT INTO t(id) VALUES (1);
                INSERT INTO t(id) VALUES (2);
                COMMIT;""");

        assertThat(result.type()).isEqualTo(QueryType.INSERT);
        assertThat(result.transactional()).isTrue();
        assertThat(result.statements()).hasSize(2);
        assertThat(result.statements().get(0)).contains("INSERT INTO t");
        assertThat(result.statements().get(1)).contains("VALUES (2)");
    }

    @Test
    void parsesUpdateTransactionRepresentativeTypeIsUpdate() {
        SqlParseResult result = service.parse(
                "BEGIN; UPDATE t SET v=1 WHERE id=1; UPDATE t SET v=2 WHERE id=2; COMMIT;");

        assertThat(result.type()).isEqualTo(QueryType.UPDATE);
        assertThat(result.transactional()).isTrue();
        assertThat(result.statements()).hasSize(2);
    }

    @Test
    void acceptsStartTransactionMarker() {
        SqlParseResult result = service.parse(
                "START TRANSACTION; INSERT INTO t(id) VALUES(1); COMMIT;");

        assertThat(result.transactional()).isTrue();
        assertThat(result.statements()).hasSize(1);
    }

    @Test
    void acceptsBeginTransactionMarker() {
        SqlParseResult result = service.parse(
                "BEGIN TRANSACTION; INSERT INTO t(id) VALUES(1); COMMIT;");

        assertThat(result.transactional()).isTrue();
    }

    @Test
    void acceptsBeginWorkMarker() {
        SqlParseResult result = service.parse(
                "BEGIN WORK; INSERT INTO t(id) VALUES(1); COMMIT WORK;");

        assertThat(result.transactional()).isTrue();
    }

    @Test
    void acceptsLowercaseAndMixedCaseMarkers() {
        SqlParseResult result = service.parse(
                "begin; INSERT INTO t(id) VALUES(1); Commit;");

        assertThat(result.transactional()).isTrue();
        assertThat(result.type()).isEqualTo(QueryType.INSERT);
    }

    @Test
    void acceptsEndAsClosingMarker() {
        SqlParseResult result = service.parse(
                "BEGIN; INSERT INTO t(id) VALUES(1); END;");

        assertThat(result.transactional()).isTrue();
    }

    @Test
    void acceptsLeadingLineAndBlockComments() {
        SqlParseResult result = service.parse("""
                -- prepare batch
                /* multi
                   line */
                BEGIN;
                INSERT INTO t(id) VALUES(1);
                COMMIT;""");

        assertThat(result.transactional()).isTrue();
    }

    @Test
    void acceptsIssueExamplePayload() {
        SqlParseResult result = service.parse("""
                BEGIN; -- start a transaction
                INSERT INTO card.pending_activation_cards_report (id, user_id, card_ids, generation_date)
                SELECT uuid_generate_v4(), user_id, STRING_AGG(id::text, ','), '2026-05-07'
                FROM card.card
                WHERE card_status = 'CREATED' AND card_type = 'PHYSICAL_DEBIT'
                AND user_id NOT IN (SELECT user_id FROM card.pending_activation_cards_report WHERE generation_date = '2026-05-07')
                GROUP BY user_id;
                INSERT INTO card.event_outbox(id, reference_id, type, created_at, event)
                VALUES(uuid_generate_v4(), 'a89117ae-c760-4f83-acd3-20b24e9a4abd', 'PENDING_ACTIVATION_CARDS_REPORT_POPULATED', now(), '{"eventId":"ea159aaf-9f96-4a91-854e-83fbac8c53ce","generationDate":"2026-05-07"}');
                COMMIT; -- commit the change (or roll it back later)
                """);

        assertThat(result.transactional()).isTrue();
        assertThat(result.type()).isEqualTo(QueryType.INSERT);
        assertThat(result.statements()).hasSize(2);
    }

    @Test
    void rejectsMixedSelectAndDmlInsideTransaction() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; SELECT * FROM t; INSERT INTO t(id) VALUES(1); COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("mix SELECT");
    }

    @Test
    void rejectsSelectOnlyTransaction() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; SELECT 1; SELECT 2; COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("SELECT does not require");
    }

    @Test
    void rejectsDdlInsideTransaction() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; CREATE TABLE t(id int); INSERT INTO t(id) VALUES(1); COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("DDL not allowed");
    }

    @Test
    void rejectsRollbackInsideTransaction() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; ROLLBACK; INSERT INTO t(id) VALUES(1); COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("ROLLBACK");
    }

    @Test
    void rejectsBeginWithoutCommitWhenLastStatementIsRollback() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; INSERT INTO t(id) VALUES(1); ROLLBACK;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("Missing closing COMMIT");
    }

    @Test
    void rejectsSavepointInsideTransaction() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; SAVEPOINT sp1; INSERT INTO t(id) VALUES(1); COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("SAVEPOINT");
    }

    @Test
    void rejectsEmptyTransactionBody() {
        assertThatThrownBy(() -> service.parse("BEGIN; COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("Empty transaction");
    }

    @Test
    void rejectsUnmatchedBegin() {
        assertThatThrownBy(() -> service.parse(
                "BEGIN; INSERT INTO t(id) VALUES(1);"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("Missing closing COMMIT");
    }

    @Test
    void rejectsUnmatchedCommit() {
        assertThatThrownBy(() -> service.parse(
                "INSERT INTO t(id) VALUES(1); COMMIT;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("COMMIT without matching BEGIN");
    }
}
