package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * The ranking heuristic behind automatic query suggestions (#776). Deliberately deterministic and
 * provider-free: the same inputs always produce the same score, the feature keeps working with AI
 * switched off, and the whole thing is exercised by a plain unit test rather than a recorded
 * completion.
 *
 * <pre>
 * score = w_freq    · ln(1 + approvedCount)
 *       + w_recency · 2 ^ (-age / halfLife)
 *       + w_overlap · jaccard(candidateTables, viewerTables)
 * </pre>
 *
 * <p>The frequency term is logarithmic so a query run hundreds of times cannot drown out everything
 * else; the recency term decays smoothly rather than cutting off at a window edge; the overlap term
 * is what personalises an org-wide corpus to one analyst without needing a per-user corpus. A score
 * is a sort key and nothing more — it is never compared against a threshold and never reaches a
 * decision path.
 */
@Component
@RequiredArgsConstructor
class QuerySuggestionScorer {

    private static final double LN_2 = Math.log(2);

    private final QuerySuggestionProperties properties;

    double score(int approvedCount, Instant lastSubmittedAt, Instant now,
                 Set<String> candidateTables, Set<String> viewerTables) {
        return properties.weightFrequency() * frequency(approvedCount)
                + properties.weightRecency() * recency(lastSubmittedAt, now)
                + properties.weightTableOverlap() * jaccard(candidateTables, viewerTables);
    }

    private double frequency(int approvedCount) {
        return Math.log1p(Math.max(0, approvedCount));
    }

    /**
     * Exponential decay on age. A row stamped in the future (clock skew between replicas) scores as
     * brand new rather than above 1, so skew can reorder nothing.
     */
    private double recency(Instant lastSubmittedAt, Instant now) {
        if (lastSubmittedAt == null || now == null) {
            return 0;
        }
        double ageSeconds = Duration.between(lastSubmittedAt, now).toSeconds();
        if (ageSeconds <= 0) {
            return 1;
        }
        double halfLifeSeconds = properties.recencyHalfLife().toSeconds();
        // ln 2, not a bare exp(-age/H): the knob is named half-life, so a row exactly one
        // half-life old must score 0.5. exp(-age/H) is an e-folding constant and would decay
        // ~44% slower than the property name promises.
        return Math.exp(-LN_2 * ageSeconds / halfLifeSeconds);
    }

    /** Jaccard similarity; zero when either side is empty, so an unknown viewer never boosts. */
    private double jaccard(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long intersection = left.stream().filter(right::contains).count();
        if (intersection == 0) {
            return 0;
        }
        long union = (long) left.size() + right.size() - intersection;
        return (double) intersection / union;
    }
}
