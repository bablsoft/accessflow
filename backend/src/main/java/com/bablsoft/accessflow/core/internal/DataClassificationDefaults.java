package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.EnumMap;
import java.util.Map;

/**
 * The stricter handling each data classification implies (AF-447). Kept in {@code core.internal}
 * (not {@code api}) because it is an implementation policy, not part of any module's contract, and
 * it references {@link MaskingStrategy}. Consumed by the classification admin service to derive
 * masking policies and to aggregate the review-posture preview.
 *
 * <p>The AI risk-bump weights are deliberately <em>not</em> here — they are an {@code ai}-module
 * concern (and that module cannot reach {@code core.internal}); see {@code ClassificationRiskBooster}.
 */
final class DataClassificationDefaults {

    /**
     * @param maskingStrategy the strategy a column-level tag derives, or {@code null} for no masking
     * @param maskingParams   strategy params (e.g. {@code visible_suffix=4})
     */
    record ClassificationDefault(
            MaskingStrategy maskingStrategy,
            Map<String, String> maskingParams,
            boolean requiresAiReview,
            boolean requiresHumanApproval,
            int minApprovals) {
    }

    private static final Map<DataClassification, ClassificationDefault> DEFAULTS =
            new EnumMap<>(DataClassification.class);

    static {
        DEFAULTS.put(DataClassification.PII,
                new ClassificationDefault(MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"),
                        true, true, 1));
        DEFAULTS.put(DataClassification.PCI,
                new ClassificationDefault(MaskingStrategy.FULL, Map.of(), true, true, 2));
        DEFAULTS.put(DataClassification.PHI,
                new ClassificationDefault(MaskingStrategy.FULL, Map.of(), true, true, 2));
        DEFAULTS.put(DataClassification.GDPR,
                new ClassificationDefault(MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"),
                        true, true, 1));
        DEFAULTS.put(DataClassification.FINANCIAL,
                new ClassificationDefault(MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"),
                        true, true, 1));
        DEFAULTS.put(DataClassification.SENSITIVE,
                new ClassificationDefault(MaskingStrategy.HASH, Map.of(), true, false, 1));
    }

    private DataClassificationDefaults() {
    }

    static ClassificationDefault forClassification(DataClassification classification) {
        return DEFAULTS.get(classification);
    }

    static boolean isComplete() {
        for (var value : DataClassification.values()) {
            if (!DEFAULTS.containsKey(value)) {
                return false;
            }
        }
        return true;
    }
}
