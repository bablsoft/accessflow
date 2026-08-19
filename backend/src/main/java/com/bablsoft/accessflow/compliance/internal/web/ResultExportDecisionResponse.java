package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;

import java.util.List;
import java.util.UUID;

record ResultExportDecisionResponse(
        boolean allowed,
        ExportPolicyMode effectiveMode,
        Integer rowCap,
        boolean watermark,
        List<UUID> policyIds,
        List<DataClassification> classificationsPresent) {

    static ResultExportDecisionResponse from(ExportDecision decision) {
        return new ResultExportDecisionResponse(
                decision.allowed(),
                decision.effectiveMode(),
                decision.rowCap(),
                decision.watermark(),
                decision.policyIds(),
                decision.classificationsPresent());
    }
}
