package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.AiBudgetExceededException;
import com.bablsoft.accessflow.ai.api.AppendHelpChatTurnCommand;
import com.bablsoft.accessflow.ai.api.HelpChatAnswer;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.internal.config.AiRateLimitProperties;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Help-chat tokens reach the monthly AI budget (AF-904, epic AF-899 decision 10).
 *
 * <p>Help turns deliberately write no {@code ai_analyses} row, so before this the budget could not
 * see them at all and a chatty agent would drain the provider key without ever tripping
 * {@code ACCESSFLOW_AI_RATE_LIMIT_TOKENS_PER_MONTH}. This drives the real limiter against a real
 * Postgres and real stored turns rather than a stubbed sum, because the thing worth proving is that
 * the query finds rows the session service actually wrote.
 *
 * <p>The limiter is constructed here rather than autowired so the budget can be a test value without
 * a property override — every override forks a second Spring context for the whole suite. The Redis
 * half is a mock that must stay untouched: with {@code requestsPerMinute = 0} the per-minute limit is
 * off, and only the DB-backed budget runs.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpChatTokenBudgetIntegrationTest {

    private static final long BUDGET = 10_000L;
    private static final int PROMPT_TOKENS = 3_100;
    private static final int COMPLETION_TOKENS = 220;

    @Autowired AiAnalysisStatsLookupService statsLookupService;
    @Autowired HelpChatSessionService sessionService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired Clock clock;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private UUID organizationId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Help Budget Org " + UUID.randomUUID());
        org.setSlug("help-budget-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("help-budget-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Help Budget User");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);
        userId = user.getId();
    }

    private DefaultAiRateLimiter limiter() {
        return new DefaultAiRateLimiter(redisTemplate, new AiRateLimitProperties(0, BUDGET),
                statsLookupService, clock);
    }

    private void storeTurns(int turns) {
        var session = sessionService.createSession(organizationId, userId);
        for (var i = 0; i < turns; i++) {
            sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId,
                    session.id(), "Question " + i,
                    new HelpChatAnswer("Answer " + i, List.of(), false, "gpt-4o",
                            PROMPT_TOKENS, COMPLETION_TOKENS),
                    "c0ac599ef7fc", 800));
        }
    }

    @Test
    void storedHelpTurnsCountAgainstTheMonthlyBudget() {
        storeTurns(2);

        var limiter = limiter();
        assertThatCode(() -> limiter.enforce(organizationId)).doesNotThrowAnyException();

        // Two more turns cross 10,000 tokens: 4 * 3,320 = 13,280.
        storeTurns(2);

        assertThatThrownBy(() -> limiter.enforce(organizationId))
                .isInstanceOf(AiBudgetExceededException.class);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void oneOrganizationsHelpSpendIsInvisibleToAnother() {
        storeTurns(4);
        var otherOrganizationId = UUID.randomUUID();

        assertThat(statsLookupService.sumHelpChatTokensSince(organizationId,
                clock.instant().minusSeconds(3600)))
                .isEqualTo(4L * (PROMPT_TOKENS + COMPLETION_TOKENS));
        assertThat(statsLookupService.sumHelpChatTokensSince(otherOrganizationId,
                clock.instant().minusSeconds(3600)))
                .isZero();
    }
}
