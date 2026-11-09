package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiConnectorQuotaExceededException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #972: a throttled execute answers with ApiConnectorQuotaExceededException, and the throttle stamp
 * survives it — both the execution service's and the calling request service's transactions declare
 * {@code noRollbackFor}.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiRequestThrottleIntegrationTest {

    @Autowired private ApiRequestService requestService;
    @Autowired private ApiConnectorRepository connectorRepository;
    @Autowired private ApiRequestRepository requestRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    void throttleStampSurvivesTheQuotaException() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var connector = new ApiConnectorEntity();
        connector.setId(UUID.randomUUID());
        connector.setOrganizationId(orgId);
        connector.setName("throttle-" + UUID.randomUUID());
        connector.setProtocol(ApiProtocol.REST);
        connector.setBaseUrl("https://api.test");
        connector.setCallsPerDay(1);
        connectorRepository.saveAndFlush(connector);
        // Exhaust today's quota so the call is refused before any upstream contact.
        var today = LocalDate.now(ZoneOffset.UTC);
        redisTemplate.opsForValue().set(ApiConnectorRateLimiter.KEY_PREFIX + "{" + connector.getId() + "}:day:"
                + today, "1");

        var request = new ApiRequestEntity();
        request.setId(UUID.randomUUID());
        request.setConnectorId(connector.getId());
        request.setOrganizationId(orgId);
        request.setSubmittedBy(userId);
        request.setVerb("GET");
        request.setRequestPath("/v1/things");
        request.setStatus(QueryStatus.APPROVED);
        requestRepository.saveAndFlush(request);

        assertThatThrownBy(() -> requestService.execute(request.getId(), orgId, userId, false))
                .isInstanceOf(ApiConnectorQuotaExceededException.class);

        var reloaded = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(reloaded.getThrottledUntil()).isNotNull();
        assertThat(reloaded.getThrottleCount()).isEqualTo(1);
    }
}
