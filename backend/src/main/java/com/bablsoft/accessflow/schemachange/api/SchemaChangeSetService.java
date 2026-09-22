package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Authoring of schema change sets (#878, epic #870; implemented in #879). Every method is
 * organization-scoped: a change set outside the caller's organization is indistinguishable from a
 * missing one. Statements are parsed and classified before they are stored, and become immutable
 * once a promotion of the set has moved past draft.
 */
public interface SchemaChangeSetService {

    PageResponse<SchemaChangeSetView> list(UUID organizationId, SchemaChangeSetListFilter filter,
                                           PageRequest pageRequest);

    SchemaChangeSetView get(UUID organizationId, UUID changeSetId);

    /** Rejects a second change set with the same name under the same pipeline. */
    SchemaChangeSetView create(UUID organizationId, UUID actorId, CreateSchemaChangeSetCommand command);

    SchemaChangeSetView update(UUID organizationId, UUID changeSetId, UpdateSchemaChangeSetCommand command);

    /** Replaces the ordered statement list wholesale; refused once the set has been promoted. */
    SchemaChangeSetView replaceStatements(UUID organizationId, UUID changeSetId,
                                          List<SchemaChangeSetStatementInput> statements);

    void delete(UUID organizationId, UUID changeSetId);
}
