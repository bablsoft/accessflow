package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.ApprovalPredictionService;
import com.bablsoft.accessflow.ai.internal.config.ApprovalPredictionProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.ApprovalPredictionModelEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.ApprovalPredictionModelRepository;
import com.bablsoft.accessflow.core.api.ApprovalPredictionLookupService;
import com.bablsoft.accessflow.core.api.ApprovalPredictionPersistenceService;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PersistApprovalPredictionCommand;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.events.ApprovalPredictionCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Serving path for approval-outcome prediction (issue AF-651): scores a query that has landed in
 * review and persists exactly one {@code approval_predictions} row, plus the {@code trainAll}
 * fan-out that keeps each organization's model fresh.
 *
 * <p><strong>Every path persists a row</strong> — a probability, a {@code skipped} row naming the
 * reason, or a {@code failed} sentinel — mirroring how AF-624 handles cost estimates, so the read
 * side can always render a definitive state rather than "nothing yet".
 *
 * <p><strong>Skip reasons are machine tokens, not localized text.</strong> A prediction is written
 * once by an async listener and read later by any reviewer; resolving a {@code MessageSource} at
 * write time would freeze whatever locale that listener thread happened to carry. The read side maps
 * the token to the reader's locale instead. Feature-schema mismatch is folded into
 * {@link #SKIP_MODEL_NOT_SERVING} to keep the token set closed — the versions go to the log, where
 * an operator investigating a stale model will look.
 *
 * <p><strong>Failures cannot reach the workflow.</strong> The methods are driven by
 * {@code @ApplicationModuleListener}s, which are asynchronous and commit-scoped, so nothing here can
 * propagate into the transition that published the event; the {@code catch} below is what makes the
 * <em>row</em> definitive, not what makes the workflow safe. One consequence worth knowing: the
 * lookups this class calls join the listener's transaction, so a lookup that throws marks that
 * transaction rollback-only and the sentinel write is lost at commit. The failures the sentinel
 * actually exists for — coefficient deserialization, feature-vector validation, scoring — are pure
 * computation and leave the transaction usable.
 */
@Service
@RequiredArgsConstructor
class DefaultApprovalPredictionService implements ApprovalPredictionService {

    /** The whole feature is switched off; no model is consulted. */
    static final String SKIP_DISABLED = "DISABLED";

    /**
     * No model row yet (cold start), the quality gate failed, or the stored model was trained
     * against a different feature schema.
     */
    static final String SKIP_MODEL_NOT_SERVING = "MODEL_NOT_SERVING";

    /** Matches the {@code VARCHAR(500)} width of {@code skipped_reason} and {@code error_message}. */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /** Organizations per page while fanning out the retrain. */
    private static final int ORGANIZATION_PAGE_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovalPredictionService.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryEstimateLookupService queryEstimateLookupService;
    private final ApprovalPredictionLookupService approvalPredictionLookupService;
    private final ApprovalPredictionPersistenceService approvalPredictionPersistenceService;
    private final ApprovalPredictionModelRepository modelRepository;
    private final ApprovalFeatureLoader featureLoader;
    private final ApprovalModelTrainingService trainingService;
    private final OrganizationAdminService organizationAdminService;
    private final ApprovalPredictionProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately does <em>not</em> check that the query is still {@code PENDING_REVIEW}. The
     * listener runs after commit and asynchronously, so a fast reviewer can decide first; a status
     * guard here would write no row at all and leave the detail view blank. Insert-once persistence
     * is what keeps this idempotent.
     */
    @Override
    public void predictForQuery(UUID queryRequestId) {
        if (!properties.enabled()) {
            persistSkipped(queryRequestId, SKIP_DISABLED);
            return;
        }
        var query = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (query == null) {
            log.warn("Approval prediction skipped: query request {} not found", queryRequestId);
            return;
        }
        score(query);
    }

    @Override
    public void refreshForLateEstimate(UUID queryRequestId) {
        if (!properties.enabled()) {
            return;
        }
        var existing = approvalPredictionLookupService.findByQueryRequestId(queryRequestId)
                .orElse(null);
        if (existing == null || existing.skipped() || existing.failed()
                || !recordedMissingEstimate(existing.featuresJson())) {
            return;
        }
        if (!hasUsableEstimate(queryRequestId)) {
            return;
        }
        var query = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (query == null || query.status() != QueryStatus.PENDING_REVIEW) {
            return;
        }
        log.debug("Re-scoring query {} now that its cost estimate has arrived", queryRequestId);
        score(query);
    }

    @Override
    public void trainAll() {
        if (!properties.enabled()) {
            log.debug("Approval prediction disabled; skipping retrain");
            return;
        }
        int page = 0;
        int trained = 0;
        int totalPages;
        do {
            var organizations = organizationAdminService.list(
                    PageRequest.of(page, ORGANIZATION_PAGE_SIZE));
            totalPages = organizations.totalPages();
            for (var organization : organizations.content()) {
                if (organization.disabled()) {
                    continue;
                }
                try {
                    trainingService.trainForOrganization(organization.id());
                    trained++;
                } catch (RuntimeException ex) {
                    log.error("Approval model training failed for org {}", organization.id(), ex);
                }
            }
            page++;
        } while (page < totalPages);
        log.info("Approval model retrain complete for {} organizations", trained);
    }

    @Override
    public void trainForOrganization(UUID organizationId) {
        trainingService.trainForOrganization(organizationId);
    }

    /**
     * The shared scoring tail: model gates, then extract, predict and persist. Any unexpected
     * failure becomes a {@code failed} sentinel row rather than an exception.
     */
    private void score(QueryRequestSnapshot query) {
        var model = modelRepository.findByOrganizationId(query.organizationId()).orElse(null);
        if (model == null || !model.isServing()) {
            persistSkipped(query.id(), SKIP_MODEL_NOT_SERVING);
            return;
        }
        if (model.getFeatureSchemaVersion() != ApprovalFeatureVector.SCHEMA_VERSION) {
            log.warn("Approval model for org {} was trained against feature schema {}, serving "
                            + "path is on {} — retrain required",
                    query.organizationId(), model.getFeatureSchemaVersion(),
                    ApprovalFeatureVector.SCHEMA_VERSION);
            persistSkipped(query.id(), SKIP_MODEL_NOT_SERVING);
            return;
        }
        try {
            scoreWithModel(query, model);
        } catch (RuntimeException ex) {
            log.error("Approval prediction failed for query {}", query.id(), ex);
            persistFailed(query.id(), ex.getMessage());
        }
    }

    private void scoreWithModel(QueryRequestSnapshot query, ApprovalPredictionModelEntity model) {
        var trained = TrainedApprovalModel.fromJson(model.getCoefficients(), objectMapper);
        if (!ApprovalFeatureVector.FEATURE_SCHEMA_V1.equals(trained.featureNames())) {
            log.warn("Approval model for org {} declares feature names that do not match schema {}"
                            + " — retrain required",
                    query.organizationId(), ApprovalFeatureVector.SCHEMA_VERSION);
            persistSkipped(query.id(), SKIP_MODEL_NOT_SERVING);
            return;
        }
        var vector = featureLoader.load(query);
        if (vector == null) {
            return;
        }
        var probability = requireProbability(trained.predict(vector.values()));
        persist(new PersistApprovalPredictionCommand(query.id(), probability, model.getId(),
                ApprovalFeatureVector.SCHEMA_VERSION,
                objectMapper.writeValueAsString(vector.asSnapshotMap()),
                false, null, false, null));
    }

    private void persistSkipped(UUID queryRequestId, String reason) {
        persist(new PersistApprovalPredictionCommand(queryRequestId, null, null, null, null,
                true, truncate(reason), false, null));
    }

    /**
     * Sentinel writes get their own guard: this runs on the failure path, where the persistence call
     * can fail for the very reason that got us here (a concurrently deleted query request throws
     * {@code IllegalStateException}). Losing the sentinel is acceptable; escaping the listener is not.
     */
    private void persistFailed(UUID queryRequestId, String message) {
        try {
            persist(new PersistApprovalPredictionCommand(queryRequestId, null, null, null, null,
                    false, null, true, truncate(message)));
        } catch (RuntimeException ex) {
            log.error("Could not persist approval-prediction failure sentinel for query {}",
                    queryRequestId, ex);
        }
    }

    private void persist(PersistApprovalPredictionCommand command) {
        var predictionId = approvalPredictionPersistenceService.persist(command);
        eventPublisher.publishEvent(new ApprovalPredictionCompletedEvent(
                command.queryRequestId(), predictionId, command.probability()));
    }

    /** Whether the persisted snapshot was taken while the cost estimate was still missing. */
    private boolean recordedMissingEstimate(String featuresJson) {
        if (featuresJson == null) {
            return false;
        }
        try {
            return objectMapper.readTree(featuresJson).path("estimate_missing").asBoolean(false);
        } catch (RuntimeException ex) {
            log.debug("Unparseable approval_predictions.features JSON; not re-scoring", ex);
            return false;
        }
    }

    /**
     * An estimate that is absent, unsupported or failed still featurizes as
     * {@code estimate_missing=true}, so re-scoring would rewrite an identical row and fire a
     * pointless completion event. Transactional queries reach here routinely: their estimate is
     * persisted {@code supported=false} and the completion event is published anyway.
     */
    private boolean hasUsableEstimate(UUID queryRequestId) {
        return queryEstimateLookupService.findByQueryRequestId(queryRequestId)
                .filter(estimate -> estimate.supported() && !estimate.failed())
                .isPresent();
    }

    /**
     * {@code TrainedApprovalModel.fromJson} does not clamp a zero stddev the way the trainer does, so
     * a hand-edited or legacy blob can drive the logit to infinity and yield {@code NaN}. Postgres
     * accepts {@code 'NaN'::double precision}, so this has to be caught before the write; the throw
     * lands on the {@code failed} sentinel path like any other scoring error.
     */
    private static double requireProbability(double probability) {
        if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalStateException(
                    "Model produced a probability outside [0,1]: " + probability);
        }
        return probability;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_MESSAGE_LENGTH ? value : value.substring(0, MAX_MESSAGE_LENGTH);
    }
}
