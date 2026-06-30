package com.bablsoft.accessflow.requestgroups.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGroupSnapshotTest {

    @Test
    void exposesIdentityAndOwnership() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var submitter = UUID.randomUUID();
        var snapshot = new RequestGroupSnapshot(id, orgId, submitter, "bundle",
                RequestGroupStatus.PENDING_REVIEW);

        assertThat(snapshot.id()).isEqualTo(id);
        assertThat(snapshot.organizationId()).isEqualTo(orgId);
        assertThat(snapshot.submittedByUserId()).isEqualTo(submitter);
        assertThat(snapshot.name()).isEqualTo("bundle");
        assertThat(snapshot.status()).isEqualTo(RequestGroupStatus.PENDING_REVIEW);
    }
}
