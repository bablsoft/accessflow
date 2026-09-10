package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads the population automatic query suggestions are mined from (#776): an organisation's
 * <em>approved</em> query requests, newest first.
 *
 * <p>"Approved" here means {@link QueryStatus#APPROVED} or {@link QueryStatus#EXECUTED} — the two
 * states a request reaches by going <em>through</em> the organisation's approval path. That is
 * deliberately wider than "a human looked at it": a routing policy's {@code AUTO_APPROVE}
 * (AF-379), a grant with {@code pre_approve_queries} (#582), and a review plan that does not
 * require human approval all land here, and all three are the organisation's own configured
 * judgement that the shape is safe. What is excluded is everything that went <em>around</em> that
 * path, or that no analyst authored:
 * {@link SubmissionReason#EMERGENCY_ACCESS} bypassed review entirely (AF-385);
 * {@link SubmissionReason#RECURRING} rows are machine-generated occurrences of a series, so a single
 * approved five-minute schedule would otherwise read as thousands of independent approvals (#627);
 * a row carrying a recurring parent is one of those occurrences seen from the other side; and a row
 * carrying a recurrence rule is the series <em>parent</em>, which is a schedule definition rather
 * than a query an analyst sat down and wrote.
 *
 * <p>A dedicated read rather than
 * {@link QueryRequestLookupService#streamCorpusForOrganization} because that corpus is the AF-630
 * policy simulator's: {@link QueryListFilter} carries a single status and neither a submission
 * reason nor a recurrence field, so not one of those four exclusions can be expressed through it.
 *
 * <p>The corpus is read <strong>per datasource</strong>, not per organisation. An organisation-wide
 * row cap would be spent almost entirely on its busiest datasource, leaving the quieter ones with
 * no suggestions at all.
 */
public interface QuerySuggestionCorpusLookupService {

    /** Datasources in the organisation with at least one qualifying approved query since {@code since}. */
    List<UUID> findDatasourceIdsWithHistory(UUID organizationId, Instant since);

    /**
     * The newest {@code maxRows} qualifying approved queries on one datasource, newest first.
     * Returns an empty list when {@code maxRows} is not positive.
     */
    List<QuerySuggestionCorpusRow> findCorpus(UUID datasourceId, Instant since, int maxRows);
}
