package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.core.api.AiAnalysisLookupService;
import com.partqam.accessflow.core.api.AiAnalysisSummaryView;
import com.partqam.accessflow.core.api.ApproverRule;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.DatasourceView;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UserQueryService;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.config.NotificationsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
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
        var props = new NotificationsProperties(
                URI.create("https://app.example.test/"),
                NotificationsProperties.Retry.defaults());
        builder = new NotificationContextBuilder(queryRequestLookup, reviewPlanLookup,
                aiLookup, datasourceAdmin, userQuery, props);

        when(queryRequestLookup.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(datasourceAdmin.getForAdmin(eq(datasourceId), eq(orgId))).thenReturn(datasourceView());
        when(userQuery.findById(submitterId)).thenReturn(Optional.of(user(submitterId,
                "alice@example.com", UserRoleType.ANALYST)));
        when(aiLookup.findByQueryRequestId(queryId)).thenReturn(Optional.of(
                new AiAnalysisSummaryView(UUID.randomUUID(), queryId, RiskLevel.HIGH, 80, "danger")));
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
        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null);
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

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
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
                new ApproverRule(null, UserRoleType.REVIEWER, 1));
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(rules, List.of())));
        var reviewer = user(reviewerId, "rev@example.com", UserRoleType.REVIEWER);
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(reviewer));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).hasSize(1);
    }

    @Test
    void querySubmittedExcludesSubmitterFromReviewerSet() {
        var rules = List.of(new ApproverRule(null, UserRoleType.REVIEWER, 1));
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(rules, List.of())));
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(user(submitterId, "alice@example.com", UserRoleType.REVIEWER)));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
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

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("stage1@example.com");
    }

    @Test
    void querySubmittedReturnsEmptyWhenPlanMissing() {
        when(reviewPlanLookup.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
                .orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void querySubmittedReturnsEmptyWhenNoApprovers() {
        when(reviewPlanLookup.findForDatasource(datasourceId))
                .thenReturn(Optional.of(plan(List.of(), List.of())));
        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
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
                "h", null, null, Instant.now());
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(inactive));

        var ctx = builder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null)
                .orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void queryApprovedRecipientsContainOnlySubmitter() {
        var reviewerId = UUID.randomUUID();
        when(userQuery.findById(reviewerId)).thenReturn(Optional.of(
                user(reviewerId, "bob@example.com", UserRoleType.ADMIN)));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId,
                reviewerId, "comment").orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactly("alice@example.com");
        assertThat(ctx.reviewerUserId()).isEqualTo(reviewerId);
        assertThat(ctx.reviewerDisplayName()).isEqualTo("Bob");
        assertThat(ctx.reviewerComment()).isEqualTo("comment");
    }

    @Test
    void queryRejectedRecipientsContainOnlySubmitter() {
        var ctx = builder.build(NotificationEventType.QUERY_REJECTED, queryId, null,
                "no thanks").orElseThrow();
        assertThat(ctx.recipients()).hasSize(1);
        assertThat(ctx.recipients().get(0).email()).isEqualTo("alice@example.com");
        assertThat(ctx.reviewerUserId()).isNull();
    }

    @Test
    void aiHighRiskRecipientsAreActiveAdmins() {
        var adminA = user(UUID.randomUUID(), "a@example.com", UserRoleType.ADMIN);
        var adminB = user(UUID.randomUUID(), "b@example.com", UserRoleType.ADMIN);
        var inactive = new UserView(UUID.randomUUID(), "c@example.com", "C",
                UserRoleType.ADMIN, orgId, false, AuthProviderType.LOCAL, "h", null, null, Instant.now());
        when(userQuery.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(adminA, adminB, inactive));

        var ctx = builder.build(NotificationEventType.AI_HIGH_RISK, queryId, null, null)
                .orElseThrow();

        assertThat(ctx.recipients()).extracting(RecipientView::email)
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
    }

    @Test
    void testEventRecipientsContainSubmitter() {
        var ctx = builder.build(NotificationEventType.TEST, queryId, null, null).orElseThrow();
        assertThat(ctx.recipients()).hasSize(1);
        assertThat(ctx.recipients().get(0).email()).isEqualTo("alice@example.com");
    }

    @Test
    void testEventReturnsEmptyRecipientsWhenSubmitterMissing() {
        when(userQuery.findById(submitterId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.TEST, queryId, null, null).orElseThrow();
        assertThat(ctx.recipients()).isEmpty();
    }

    @Test
    void buildHandlesMissingAiAnalysisGracefully() {
        when(aiLookup.findByQueryRequestId(queryId)).thenReturn(Optional.empty());
        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null)
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
                        longSql, QueryType.SELECT, QueryStatus.PENDING_REVIEW)));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null)
                .orElseThrow();
        assertThat(ctx.sqlPreview200()).hasSize(201).endsWith("…");
        assertThat(ctx.sqlPreview300()).hasSize(301).endsWith("…");
    }

    @Test
    void truncateHandlesNullSqlAsEmptyString() {
        when(queryRequestLookup.findById(queryId)).thenReturn(Optional.of(
                new QueryRequestSnapshot(queryId, datasourceId, orgId, submitterId,
                        null, QueryType.SELECT, QueryStatus.PENDING_REVIEW)));

        var ctx = builder.build(NotificationEventType.QUERY_APPROVED, queryId, null, null)
                .orElseThrow();
        assertThat(ctx.sqlPreview200()).isEmpty();
        assertThat(ctx.sqlPreview300()).isEmpty();
    }

    @Test
    void buildReviewUrlPreservesNonTrailingSlashBase() {
        var props = new NotificationsProperties(
                URI.create("https://no-trailing.example"),
                NotificationsProperties.Retry.defaults());
        var b = new NotificationContextBuilder(queryRequestLookup, reviewPlanLookup,
                aiLookup, datasourceAdmin, userQuery, props);
        var ctx = b.build(NotificationEventType.QUERY_APPROVED, queryId, null, null)
                .orElseThrow();
        assertThat(ctx.reviewUrl().toString())
                .isEqualTo("https://no-trailing.example/queries/" + queryId);
    }

    private QueryRequestSnapshot snapshot() {
        return new QueryRequestSnapshot(queryId, datasourceId, orgId, submitterId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_REVIEW);
    }

    private UserView user(UUID id, String email, UserRoleType role) {
        return new UserView(id, email, "Bob", role, orgId, true, AuthProviderType.LOCAL,
                "hash", null, null, Instant.now());
    }

    private DatasourceView datasourceView() {
        return new DatasourceView(datasourceId, orgId, "Production", DbType.POSTGRESQL,
                "host", 5432, "db", "user", SslMode.DISABLE, 5, 1000,
                false, true, UUID.randomUUID(), true, true, Instant.now());
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
