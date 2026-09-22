package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Authoring of schema change sets (#878, epic #870; implemented in #879). Every method is
 * organization-scoped: a change set outside the caller's organization is indistinguishable from a
 * missing one ({@link SchemaChangeSetNotFoundException}, never a 403).
 *
 * <p>Every statement passes the validation gate before it is stored: the pipeline's environments
 * that bind a datasource are the targets ({@link SchemaChangeSetNoTargetDatasourceException} when
 * statements are supplied and there is none); a transaction envelope or multi-statement text, a
 * parse failure for any target engine, and a {@code SELECT} / {@code INSERT} / {@code UPDATE} /
 * {@code DELETE} classification are each refused ({@link SchemaChangeSetStatementInvalidException});
 * the deterministic SQL review ruleset of every target is evaluated and a {@code BLOCK} finding
 * refuses the save ({@link SchemaChangeSetStatementBlockedException}) while {@code WARN} findings
 * ride on the returned view's {@code reviewWarnings}. The statement count is capped
 * ({@link SchemaChangeSetStatementLimitException}).
 *
 * <p>Statements are mutable only while every promotion of the set is absent, {@code FAILED} or
 * {@code CANCELLED} ({@link SchemaChangeSetFrozenException} otherwise, also for {@code delete}) and
 * the set is not {@code ARCHIVED} ({@link SchemaChangeSetArchivedException}). {@code update} may
 * move {@code status} to {@code ARCHIVED} only ({@link SchemaChangeSetStatusTransitionException});
 * {@code ACTIVE} is set by the promotion service.
 */
public interface SchemaChangeSetService {

    PageResponse<SchemaChangeSetView> list(UUID organizationId, SchemaChangeSetListFilter filter,
                                           PageRequest pageRequest);

    SchemaChangeSetView get(UUID organizationId, UUID changeSetId);

    /** Rejects a second change set with the same name under the same pipeline. */
    SchemaChangeSetView create(UUID organizationId, UUID actorId, CreateSchemaChangeSetCommand command);

    SchemaChangeSetView update(UUID organizationId, UUID changeSetId, UpdateSchemaChangeSetCommand command);

    /** Replaces the ordered statement list wholesale (an empty list clears it); refused once frozen or archived. */
    SchemaChangeSetView replaceStatements(UUID organizationId, UUID changeSetId,
                                          List<SchemaChangeSetStatementInput> statements);

    /** Deletes the set and its statements; refused once frozen. */
    void delete(UUID organizationId, UUID changeSetId);
}
