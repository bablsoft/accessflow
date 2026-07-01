package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.EnumMap;
import java.util.Map;

/**
 * The stricter handling each data classification implies for an API connector (AF-518) — the apigov
 * mirror of {@code core.internal.DataClassificationDefaults}. Lives in {@code apigov.internal}
 * (modules cannot reach another module's {@code internal}) and references {@link MaskingStrategy}.
 * Consumed by the classification admin service to derive masking policies and to aggregate the
 * review-posture preview. AI risk-bump weights are <em>not</em> here — see
 * {@code ApiConnectorClassificationRiskBooster}.
 */
final class ApiConnectorClassificationDefaults {

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

    private ApiConnectorClassificationDefaults() {
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
