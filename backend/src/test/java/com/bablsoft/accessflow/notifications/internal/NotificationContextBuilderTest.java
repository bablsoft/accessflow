package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignLookupService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignSummary;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.LocalizationConfigView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationContextBuilderTest {

    private QueryRequestLookupService queryRequestLookup;
    private ReviewPlanLookupService reviewPlanLookup;
    private AiAnalysisLookupService aiLookup;
    private DatasourceAdminService datasourceAdmin;
    private UserQueryService userQuery;
    private LocalizationConfigService localizationConfig;
    private BehaviorAnomalyLookupService behaviorAnomalyLookup;
    private AttestationCampaignLookupService attestationLookup;
    private com.bablsoft.accessflow.apigov.api.ApiRequestNotificationLookupService apiRequestLookup;
    private com.bablsoft.accessflow.apigov.api.ApiConnectorNotificationLookupService apiConnectorLookup;
    private NotificationContextBuilder builder;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        queryRequestLookup = mock(QueryRequestLookupService.class);
        reviewPlanLookup = mock(ReviewPlanLookupService.class);
        aiLookup = mock(AiAnalysisLookupService.class);
        datasourceAdmin = mock(DatasourceAdminService.class);
        userQuery = mock(UserQueryService.class);
        localizationConfig = mock(LocalizationConfigService.class);
        behaviorAnomalyLookup = mock(BehaviorAnomalyLookupService.class);
        attestationLookup = mock(AttestationCampaignLookupService.class);
        apiRequestLookup = mock(com.bablsoft.accessflow.apigov.api.ApiRequestNotificationLookupService.class);
        apiConnectorLookup = mock(com.bablsoft.accessflow.apigov.api.ApiConnectorNotificationLookupService.class);
        var props = new NotificationsProperties(
                URI.create("https://app.example.test/"),
                NotificationsProperties.Retry.defaults(),
                null,
                null);
        builder = new NotificationContextBuilder(queryRequestLookup, reviewPlanLookup,
                aiLookup, datasourceAdmin, userQuery, localizationConfig, behaviorAnomalyLookup,
                attestationLookup, apiRequestLookup, apiConnectorLookup, props);

        when(queryRequestLookup.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(datasourceAdmin.getForAdmin(eq(datasourceId), eq(orgId))).thenReturn(datasourceView());
        when(userQuery.findById(submitterId)).thenReturn(Optional.of(user(submitterId,
                "alice@example.com", UserRoleType.ANALYST)));
        when(aiLookup.findByQueryRequestId(queryId)).thenReturn(Optional.of(
                new AiAnalysisSummaryView(UUID.randomUUID(), queryId, RiskLevel.HIGH, 80, "danger",
                        false, null)));
        when(localizationConfig.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en"), "en", "en"));
    }

    @Test
    void lookupPlanChannelIdsReturnsEmptyWhenPlanMissing() {
        when(reviewPlanLookup.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        assertThat(builder.lookupPlanChannelIds(datasourceId)).isEmpty();
    }

    @Test
    void lookupPlanChannelIdsForwardsListFromSnapshot() {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(List.of(), ids)));
        assertThat(builder.lookupPlanChannelIds(datasourceId)).containsExactlyElementsOf(ids);
    }

    @Test
    void buildReturnsEmptyWhenQueryUnknown() {
        when(queryRequestLookup.findById(queryId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null);
        assertThat(ctx).isEmpty();
    }

    @Test
    void buildPopulatesAllFieldsForQuerySubmittedFromExplicitApprover() {
        var reviewerId = UUID.randomUUID();
        var rule = new ApproverRule(reviewerId, null, 1);
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(List.of(rule), List.of())));
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(user(reviewerId,
                "rev@example.com", UserRoleType.REVIEWER)));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.organizationId()).isEqualTo(orgId);
        assertThat(ctx.datasourceName()).isEqualTo("Production");
        assertThat(ctx.submitterEmail()).isEqualTo("alice@example.com");
        assertThat(ctx.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(ctx.aiSummary()).isEqualTo("danger");
        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("rev@example.com");
        assertThat(ctx.reviewUrl().toString()).isEqualTo(
                "https://app.example.test/queries/" + queryId);
    }

    @Test
    void querySubmittedDeduplicatesRoleAndUserBasedRules() {
        var reviewerId = UUID.randomUUID();
        var rules = List.of(
                new ApproverRule(reviewerId, null, 1),
                new ApproverRule(null, "REVIEWER", 1));
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(rules, List.of())));
        var reviewer = user(reviewerId, "rev@example.com", UserRoleType.REVIEWER);
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(reviewer));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).hasSize(1);
    }

    @Test
    void querySubmittedExcludesSubmitterFromReviewerSet() {
        var rules = List.of(new ApproverRule(null, "REVIEWER", 1));
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(rules, List.of())));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(user(submitterId, "alice@example.com", UserRoleType.REVIEWER)));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void querySubmittedTakesOnlyLowestStage() {
        var stage1Reviewer = UUID.randomUUID();
        var stage2Reviewer = UUID.randomUUID();
        var rules = List.of(
                new ApproverRule(stage1Reviewer, null, 1),
                new ApproverRule(stage2Reviewer, null, 2));
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(rules, List.of())));
        when(userQuery.findById(stage1Reviewer)).thenReturn(Optional.of(
                user(stage1Reviewer, "stage1@example.com", UserRoleType.REVIEWER)));
        when(userQuery.findById(stage2Reviewer)).thenReturn(Optional.of(
                user(stage2Reviewer, "stage2@example.com", UserRoleType.REVIEWER)));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("stage1@example.com");
    }

    @Test
    void querySubmittedReturnsEmptyWhenPlanMissing() {
        when(reviewPlanLookup.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void querySubmittedReturnsEmptyWhenNoApprovers() {
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(List.of(), List.of())));
        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void querySubmittedSkipsInactiveReviewers() {
        var reviewerId = UUID.randomUUID();
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(List.of(new ApproverRule(reviewerId, null, 1)), List.of())));
        var inactive = new UserView(reviewerId, "rev@example.com", "Rev",
                UserRoleType.REVIEWER, orgId, false, AuthProviderType.LOCAL,
                "h", null, null, false, Instant.now());
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(inactive));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void queryApprovedRecipientsContainOnlySubmitter() {
        var reviewerId = UUID.randomUUID();
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(
                user(reviewerId, "bob@example.com", UserRoleType.ADMIN)));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId,
                reviewerId, "comment", null).orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("alice@example.com");
        assertThat(ctx.reviewerUserId()).isEqualTo(reviewerId);
        assertThat(ctx.reviewerDisplayName()).isEqualTo("Bob");
        assertThat(ctx.reviewerComment()).isEqualTo("comment");
    }

    @Test
    void queryRejectedRecipientsContainOnlySubmitter() {
        var ctx = builder.build(NotificationEventType.QUERY_REJECTED, queryId, null,
                "no thanks", null).orElseThrow();
        assertThat(ctx.recipients()).hasSize(1);
        assertThat(ctx.recipients().get(0).email()).isEqualTo("alice@example.com");
        assertThat(ctx.reviewerUserId()).isNull();
    }

    @Test
    void aiHighRiskRecipientsAreActiveAdmins() {
        var adminA = user(UUID.randomUUID(), "a@example.com", UserRoleType.ADMIN);
        var adminB = user(UUID.randomUUID(), "b@example.com", UserRoleType.ADMIN);
        var inactive = new UserView(UUID.randomUUID(), "c@example.com", "C",
                UserRoleType.ADMIN, orgId, false, AuthProviderType.LOCAL, "h", null, null, false, Instant.now());
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(adminA, adminB, inactive));

        var ctx = builder.build(NotificationEventType.AI_HIGH_RISK, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
    }

    @Test
    void testEventRecipientsContainSubmitter() {
        var ctx = builder.build(NotificationEventType.TEST, queryId, null, null, null).orElseThrow();
        assertThat(ctx.recipients()).hasSize(1);
        assertThat(ctx.recipients().get(0).email()).isEqualTo("alice@example.com");
    }

    @Test
    void testEventReturnsEmptyRecipientsWhenSubmitterMissing() {
        when(userQuery.findById(submitterId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.TEST, queryId, null, null, null).orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void buildHandlesMissingAiAnalysisGracefully() {
        when(aiLookup.findByQueryRequestId(queryId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.riskLevel()).isNull();
        assertThat(ctx.riskScore()).isNull();
        assertThat(ctx.aiSummary()).isNull();
    }

    @Test
    void truncateProducesEllipsisWhenSqlExceedsLimit() {
        var longSql = "X".repeat(500);
        when(queryRequestLookup.findById(queryId)).thenReturn(Optional.of(
                new QueryRequestSnapshot(queryId, datasourceId, orgId, submitterId,
                        longSql, QueryType.SELECT, false, QueryStatus.PENDING_REVIEW, null,
                        null, null, false)));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.sqlPreview200()).hasSize(201).endsWith("…");
        assertThat(ctx.sqlPreview300()).hasSize(301).endsWith("…");
    }

    @Test
    void truncateHandlesNullSqlAsEmptyString() {
        when(queryRequestLookup.findById(queryId)).thenReturn(Optional.of(
                new QueryRequestSnapshot(queryId, datasourceId, orgId, submitterId,
                        null, QueryType.SELECT, false, QueryStatus.PENDING_REVIEW, null,
                        null, null, false)));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.sqlPreview200()).isEmpty();
        assertThat(ctx.sqlPreview300()).isEmpty();
    }

    @Test
    void buildReviewUrlPreservesNonTrailingSlashBase() {
        var props = new NotificationsProperties(
                URI.create("https://no-trailing.example"),
                NotificationsProperties.Retry.defaults(),
                null,
                null);
        var b = new NotificationContextBuilder(queryRequestLookup, reviewPlanLookup,
                aiLookup, datasourceAdmin, userQuery, localizationConfig, behaviorAnomalyLookup,
                attestationLookup, apiRequestLookup, apiConnectorLookup, props);
        var ctx = b.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();
        assertThat(ctx.reviewUrl().toString())
                .isEqualTo("https://no-trailing.example/queries/" + queryId);
    }

    @Test
    void buildPopulatesApprovalTimeoutHoursForReviewTimeout() {
        var ctx = builder.build(NotificationEventType.REVIEW_TIMEOUT, queryId, null, null, 24)
                .orElseThrow();
        assertThat(ctx.approvalTimeoutHours()).isEqualTo(24);
        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("alice@example.com");
    }

    @Test
    void reviewTimeoutFansOutToSubmitterAndActiveOrgAdmins() {
        var adminA = user(UUID.randomUUID(), "admin-a@example.com", UserRoleType.ADMIN);
        var adminB = user(UUID.randomUUID(), "admin-b@example.com", UserRoleType.ADMIN);
        var inactiveAdmin = new UserView(UUID.randomUUID(), "inactive@example.com", "Inactive",
                UserRoleType.ADMIN, orgId, false, AuthProviderType.LOCAL, "h", null, null, false,
                Instant.now());
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(adminA, adminB, inactiveAdmin));

        var ctx = builder.build(NotificationEventType.REVIEW_TIMEOUT, queryId, null, null, 24)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("alice@example.com", "admin-a@example.com", "admin-b@example.com");
    }

    @Test
    void reviewTimeoutDeduplicatesSubmitterWhoIsAlsoAnAdmin() {
        var submitterAdmin = user(submitterId, "alice@example.com", UserRoleType.ADMIN);
        var otherAdmin = user(UUID.randomUUID(), "admin-b@example.com", UserRoleType.ADMIN);
        when(userQuery.findById(submitterId)).thenReturn(Optional.of(submitterAdmin));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(submitterAdmin, otherAdmin));

        var ctx = builder.build(NotificationEventType.REVIEW_TIMEOUT, queryId, null, null, 24)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("alice@example.com", "admin-b@example.com");
    }

    @Test
    void reviewTimeoutWithoutSubmitterStillFansOutToAdmins() {
        when(userQuery.findById(submitterId)).thenReturn(Optional.empty());
        var admin = user(UUID.randomUUID(), "admin@example.com", UserRoleType.ADMIN);
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(admin));

        var ctx = builder.build(NotificationEventType.REVIEW_TIMEOUT, queryId, null, null, 24)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("admin@example.com");
    }

    @Test
    void buildLeavesApprovalTimeoutHoursNullForNonTimeoutEvents() {
        var approved = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();
        assertThat(approved.approvalTimeoutHours()).isNull();

        var rejected = builder.build(NotificationEventType.QUERY_REJECTED, queryId, null, null, null)
                .orElseThrow();
        assertThat(rejected.approvalTimeoutHours()).isNull();
    }

    @Test
    void buildPopulatesLocaleFromOrgDefault() {
        when(localizationConfig.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en", "es"), "es", "en"));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.locale()).isEqualTo("es");
    }

    @Test
    void buildPassesThroughNullLocaleFromOrgConfig() {
        when(localizationConfig.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of(), null, null));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null, null)
                .orElseThrow();

        assertThat(ctx.locale()).isNull();
    }

    @Test
    void buildWeeklyDigestResolvesOwnerRecipientAndCarriesCounts() {
        var userId = UUID.randomUUID();
        when(userQuery.findById(userId))
                .thenReturn(Optional.of(user(userId, "me@example.com", UserRoleType.ANALYST)));
        var event = new WeeklyDigestReadyEvent(orgId, userId,
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 5, 2, 1, 3);

        var ctx = builder.buildWeeklyDigest(event).orElseThrow();

        assertThat(ctx.eventType()).isEqualTo(NotificationEventType.WEEKLY_DIGEST);
        assertThat(ctx.recipients()).extracting(RecipientView::email).containsExactly("me@example.com");
        assertThat(ctx.reviewUrl().toString()).isEqualTo("https://app.example.test/dashboard");
        assertThat(ctx.digest().totalQueries()).isEqualTo(5);
        assertThat(ctx.digest().pendingApprovals()).isEqualTo(2);
        assertThat(ctx.digest().openAnomalies()).isEqualTo(1);
        assertThat(ctx.digest().openSuggestions()).isEqualTo(3);
    }

    @Test
    void buildWeeklyDigestEmptyWhenUserMissingOrInactive() {
        var userId = UUID.randomUUID();
        when(userQuery.findById(userId)).thenReturn(Optional.empty());
        var event = new WeeklyDigestReadyEvent(orgId, userId,
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 0, 0, 0, 0);
        assertThat(builder.buildWeeklyDigest(event)).isEmpty();
    }

    @Test
    void buildAttestationCampaignResolvesRecipientsAndFields() {
        var campaignId = UUID.randomUUID();
        var recipientId = UUID.randomUUID();
        when(attestationLookup.findSummary(campaignId)).thenReturn(Optional.of(
                new AttestationCampaignSummary(campaignId, orgId, "Q3 review",
                        Instant.parse("2026-07-08T00:00:00Z"))));
        when(attestationLookup.findRecipientUserIds(campaignId))
                .thenReturn(java.util.Set.of(recipientId));
        when(userQuery.findById(recipientId)).thenReturn(Optional.of(
                user(recipientId, "rev@example.com", UserRoleType.REVIEWER)));
        when(localizationConfig.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en"), "en", "en"));

        var ctx = builder.buildAttestationCampaign(campaignId, orgId).orElseThrow();

        assertThat(ctx.eventType()).isEqualTo(NotificationEventType.ATTESTATION_CAMPAIGN_OPENED);
        assertThat(ctx.attestationCampaignName()).isEqualTo("Q3 review");
        assertThat(ctx.attestationDueAt()).isEqualTo(Instant.parse("2026-07-08T00:00:00Z"));
        assertThat(ctx.recipients()).extracting(RecipientView::userId).containsExactly(recipientId);
        assertThat(ctx.reviewUrl().toString()).endsWith("/reviews/attestations");
    }

    @Test
    void buildLifecycleErasureNotifiesActiveSubmitter() {
        var submitter = UUID.randomUUID();
        when(userQuery.findById(submitter)).thenReturn(Optional.of(
                user(submitter, "subject@example.com", UserRoleType.ANALYST)));
        when(localizationConfig.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en"), "en", "en"));

        var ctx = builder.buildLifecycleErasure(orgId, submitter).orElseThrow();

        assertThat(ctx.eventType()).isEqualTo(NotificationEventType.ERASURE_APPROVED);
        assertThat(ctx.recipients()).extracting(RecipientView::userId).containsExactly(submitter);
        assertThat(ctx.queryRequestId()).isNull();
    }

    @Test
    void buildLifecycleErasureEmptyWhenSubmitterUnknown() {
        var submitter = UUID.randomUUID();
        when(userQuery.findById(submitter)).thenReturn(Optional.empty());

        assertThat(builder.buildLifecycleErasure(orgId, submitter)).isEmpty();
    }

    @Test
    void buildApiRequestPutsIdInApiRequestSlotNotQuerySlot() {
        var apiRequestId = UUID.randomUUID();
        when(apiRequestLookup.find(apiRequestId)).thenReturn(Optional.of(
                new com.bablsoft.accessflow.apigov.api.ApiRequestNotificationView(
                        apiRequestId, orgId, UUID.randomUUID(), "Payments API", submitterId,
                        "POST", "/v1/charges",
                        com.bablsoft.accessflow.core.api.SubmissionReason.USER_SUBMITTED,
                        RiskLevel.LOW, 10, "ok")));

        var ctx = builder.buildApiRequest(
                NotificationEventType.API_REQUEST_SUBMITTED, apiRequestId).orElseThrow();

        assertThat(ctx.eventType()).isEqualTo(NotificationEventType.API_REQUEST_SUBMITTED);
        assertThat(ctx.queryRequestId()).isNull();
        assertThat(ctx.apiRequestId()).isEqualTo(apiRequestId);
        assertThat(ctx.datasourceName()).isEqualTo("Payments API");
        assertThat(ctx.reviewUrl().toString())
                .isEqualTo("https://app.example.test/api-requests/" + apiRequestId);
    }

    @Test
    void buildApiRequestEmptyWhenRequestMissing() {
        var apiRequestId = UUID.randomUUID();
        when(apiRequestLookup.find(apiRequestId)).thenReturn(Optional.empty());
        assertThat(builder.buildApiRequest(
                NotificationEventType.API_REQUEST_SUBMITTED, apiRequestId)).isEmpty();
    }

    @Test
    void buildAttestationCampaignEmptyWhenCampaignMissing() {
        var campaignId = UUID.randomUUID();
        when(attestationLookup.findSummary(campaignId)).thenReturn(Optional.empty());
        assertThat(builder.buildAttestationCampaign(campaignId, orgId)).isEmpty();
    }

    private QueryRequestSnapshot snapshot() {
        return new QueryRequestSnapshot(queryId, datasourceId, orgId, submitterId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_REVIEW, null,
                null, null, false);
    }

    @Test
    void buildApiConnectorTargetsActiveAdminsWithConnectorUrl() {
        var connectorId = UUID.randomUUID();
        when(apiConnectorLookup.find(connectorId)).thenReturn(Optional.of(
                new com.bablsoft.accessflow.apigov.api.ApiConnectorNotificationView(connectorId, orgId, "Stripe")));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(user(UUID.randomUUID(), "admin@example.com", UserRoleType.ADMIN)));

        var ctx = builder.buildApiConnector(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId).orElseThrow();

        assertThat(ctx.datasourceName()).isEqualTo("Stripe");
        assertThat(ctx.recipients()).extracting(RecipientView::email).contains("admin@example.com");
        assertThat(ctx.reviewUrl().toString()).contains("/api-connectors/" + connectorId);
    }

    @Test
    void buildApiConnectorEmptyWhenUnknown() {
        var connectorId = UUID.randomUUID();
        when(apiConnectorLookup.find(connectorId)).thenReturn(Optional.empty());
        assertThat(builder.buildApiConnector(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId)).isEmpty();
    }

    private UserView user(UUID id, String email, UserRoleType role) {
        return new UserView(id, email, "Bob", role, orgId, true, AuthProviderType.LOCAL,
                "hash", null, null, false, Instant.now());
    }

    private DatasourceView datasourceView() {
        return new DatasourceView(datasourceId, orgId, "Production", DbType.POSTGRESQL,
                "host", 5432, "db", "user", SslMode.DISABLE, 5, 1000,
                false, true, UUID.randomUUID(), true, null, false, null, null, null,
                null, null, true, Instant.now());
    }

    private ReviewPlanSnapshot plan(List<ApproverRule> rules, List<UUID> notifyChannelIds) {
        var maxStage = rules.stream().mapToInt(ApproverRule::stage).max().orElse(0);
        return new ReviewPlanSnapshot(UUID.randomUUID(), orgId, true, true, 1, false,
                maxStage, rules, notifyChannelIds);
    }

    private static <T> T any(Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
