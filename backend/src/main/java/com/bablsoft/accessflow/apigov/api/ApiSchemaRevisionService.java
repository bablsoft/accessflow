package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Scheduled-refresh drift detection for {@code sourceUrl} schemas (#893). A refresh stores a
 * hash-deduplicated revision plus the drift findings against the governed catalog and never changes
 * that catalog — only {@link #accept} does. Every method is organization-scoped through the connector:
 * a connector outside {@code organizationId} throws {@link ApiConnectorNotFoundException}.
 */
public interface ApiSchemaRevisionService {

    /**
     * Fetches, parses and diffs the schema's {@code sourceUrl} now. A fetch or parse failure is
     * recorded on the schema's {@code lastRefreshError} and rethrown ({@link ApiSchemaFetchException} /
     * {@link ApiSchemaParseException}); it produces no revision and no finding.
     */
    ApiSchemaRefreshResult refresh(UUID connectorId, UUID organizationId, UUID schemaId);

    /** {@code refreshIntervalMinutes} {@code null} disables the scheduled refresh. */
    ApiSchemaView updateRefreshPolicy(UUID connectorId, UUID organizationId, UUID schemaId,
                                      Integer refreshIntervalMinutes);

    /** Newest first. */
    List<ApiSchemaRevisionView> listRevisions(UUID connectorId, UUID organizationId, UUID schemaId);

    /** Findings across every schema of the connector, newest first; {@code status} {@code null} = all. */
    List<ApiSchemaDriftFindingView> listFindings(UUID connectorId, UUID organizationId,
                                                 ApiSchemaDriftStatus status);

    /**
     * Makes {@code revisionId} the governed catalog of the schema, in place, and marks every open
     * finding of the schema accepted.
     */
    AcceptedApiSchemaRevision accept(UUID connectorId, UUID organizationId, UUID schemaId, UUID revisionId,
                                     UUID acceptedBy);

    /** Marks an open finding dismissed; returns {@code false} when it was no longer open. */
    boolean dismissFinding(UUID connectorId, UUID organizationId, UUID findingId, UUID resolvedBy);
}
