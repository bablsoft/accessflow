package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** Test-only bridge so the persistence integration test can exercise the package-private listing specification. */
public final class SchemaChangeSetSpecificationsAccess {

    private SchemaChangeSetSpecificationsAccess() {
    }

    public static Specification<SchemaChangeSetEntity> forStatus(UUID organizationId, SchemaChangeSetStatus status) {
        return SchemaChangeSetSpecifications.forFilter(organizationId, new SchemaChangeSetListFilter(null, status));
    }
}
