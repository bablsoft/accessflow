package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceReportRowsTest {

    @Test
    void classifiedAccessRowNullListsBecomeEmpty() {
        var row = new ClassifiedAccessReportRow(UUID.randomUUID(), UUID.randomUUID(), "ds",
                UUID.randomUUID(), "a@x.com", QueryType.SELECT, null, null, null, Instant.EPOCH);

        assertThat(row.referencedTables()).isEmpty();
        assertThat(row.matched()).isEmpty();
    }

    @Test
    void regulatoryRowNullApproversBecomeEmpty() {
        var row = new RegulatoryAuditTrailRow(UUID.randomUUID(), UUID.randomUUID(), "ds",
                UUID.randomUUID(), "a@x.com", QueryType.DDL, "CREATE TABLE t (id int)", null,
                Instant.EPOCH);

        assertThat(row.approvers()).isEmpty();
    }
}
