package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationStatus;
import com.bablsoft.accessflow.deploygov.internal.DeploymentRequestStateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentVerificationEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentVerificationRepository;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationConfigCodec.VerificationConfigParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One verification tick for one request (#1025). The checks — outbound HTTP, each bounded by
 * {@code verification-request-timeout} — run outside any transaction so no pooled connection is held
 * across them (the {@code DefaultApiConnectorHealthService} precedent). The samples and any
 * transition are then written in one short transaction against a re-read row: a request that left
 * {@code RUNNING} meanwhile (a racing outcome report) drops the tick.
 */
@Service
@Slf4j
public class DefaultDeploymentVerificationService implements DeploymentVerificationService {

    static final String UNREADABLE_CONFIG = "verification config unreadable";

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentVerificationRepository verificationRepository;
    private final DeploymentRequestStateService stateService;
    private final DeploymentVerificationConfigCodec codec;
    private final VerificationCheckEvaluators evaluators;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DefaultDeploymentVerificationService(DeploymentRequestRepository requestRepository,
                                                DeploymentVerificationRepository verificationRepository,
                                                DeploymentRequestStateService stateService,
                                                DeploymentVerificationConfigCodec codec,
                                                VerificationCheckEvaluators evaluators,
                                                Clock clock,
                                                PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.verificationRepository = verificationRepository;
        this.stateService = stateService;
        this.codec = codec;
        this.evaluators = evaluators;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public VerificationTickResult verifyOne(UUID deploymentRequestId) {
        var request = requestRepository.findById(deploymentRequestId).orElse(null);
        if (request == null || !isRunning(request)) {
            return VerificationTickResult.SKIPPED;
        }
        DeploymentVerificationConfig config;
        try {
            config = codec.fromJson(request.getVerificationConfig());
        } catch (VerificationConfigParseException ex) {
            config = null;
        }
        if (config == null) {
            // Fail closed: a running verification whose snapshot cannot be read must not pass.
            log.warn("Deployment request {} has an unreadable verification snapshot",
                    deploymentRequestId);
            return stateService.markVerificationFailed(deploymentRequestId, UNREADABLE_CONFIG)
                    ? VerificationTickResult.BREACHED : VerificationTickResult.SKIPPED;
        }
        var now = clock.instant();
        if (request.getLastVerifiedAt() != null
                && now.isBefore(request.getLastVerifiedAt().plus(config.sampleInterval()))) {
            return VerificationTickResult.SKIPPED;
        }
        var samples = config.checks().stream().map(this::evaluate).toList();
        var finalConfig = config;
        var result = transactionTemplate.execute(status ->
                record(deploymentRequestId, finalConfig, samples, now));
        return result == null ? VerificationTickResult.SKIPPED : result;
    }

    private VerificationTickResult record(UUID id, DeploymentVerificationConfig config,
                                          List<VerificationSample> samples, Instant now) {
        var request = requestRepository.findByIdForUpdate(id).orElse(null);
        if (request == null || !isRunning(request)) {
            return VerificationTickResult.SKIPPED;
        }
        for (var sample : samples) {
            verificationRepository.save(toEntity(request, sample, now));
        }
        request.setLastVerifiedAt(now);
        requestRepository.save(request);
        verificationRepository.flush();
        for (var check : config.checks()) {
            long failed = verificationRepository.countByDeploymentRequestIdAndCheckNameAndPassedFalse(
                    id, check.name());
            if (failed >= config.failureThreshold()) {
                var detail = check.name() + ": " + failed + "/" + config.failureThreshold()
                        + " failed samples";
                return stateService.markVerificationFailed(id, detail)
                        ? VerificationTickResult.BREACHED : VerificationTickResult.SKIPPED;
            }
        }
        if (request.getVerificationStartedAt() != null
                && !now.isBefore(request.getVerificationStartedAt().plus(config.window()))) {
            return stateService.markVerificationPassed(id)
                    ? VerificationTickResult.PASSED : VerificationTickResult.SKIPPED;
        }
        return VerificationTickResult.SAMPLED;
    }

    private VerificationSample evaluate(VerificationCheck check) {
        try {
            return evaluators.evaluate(check);
        } catch (RuntimeException ex) {
            // Evaluators promise never to throw; if one does, the sample fails rather than passes.
            log.warn("Verification check {} threw", check.name(), ex);
            return new VerificationSample(check.name(), check.kind(), false, null,
                    "evaluation error: " + ex.getClass().getSimpleName());
        }
    }

    private static DeploymentVerificationEntity toEntity(DeploymentRequestEntity request,
                                                         VerificationSample sample, Instant now) {
        var entity = new DeploymentVerificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setDeploymentRequestId(request.getId());
        entity.setOrganizationId(request.getOrganizationId());
        entity.setCheckName(sample.checkName());
        entity.setCheckKind(sample.kind());
        entity.setSampledAt(now);
        entity.setPassed(sample.passed());
        entity.setObservedValue(sample.observedValue());
        entity.setDetail(sample.detail());
        return entity;
    }

    private static boolean isRunning(DeploymentRequestEntity request) {
        return request.getStatus() == QueryStatus.EXECUTED
                && request.getVerificationStatus() == VerificationStatus.RUNNING;
    }
}
