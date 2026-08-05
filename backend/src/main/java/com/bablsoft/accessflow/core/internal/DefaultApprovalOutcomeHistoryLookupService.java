package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeHistoryLookupService;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.ApprovalRateCounts;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApprovalOutcomeHistoryLookupService implements ApprovalOutcomeHistoryLookupService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultApprovalOutcomeHistoryLookupService.class);

    private final QueryRequestRepository queryRequestRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalOutcomeSample> findDecidedSamples(UUID organizationId, Instant since,
                                                          int maxRows) {
        if (maxRows <= 0) {
            return List.of();
        }
        var rows = queryRequestRepository.findApprovalOutcomeSampleRows(
                organizationId, since, PageRequest.of(0, maxRows));
        return rows.stream().map(this::toSample).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalRateCounts submitterCounts(UUID organizationId, UUID userId, Instant since) {
        return toCounts(queryRequestRepository.countApprovalOutcomesBySubmitter(
                organizationId, userId, since));
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalRateCounts datasourceCounts(UUID organizationId, UUID datasourceId,
                                               Instant since) {
        return toCounts(queryRequestRepository.countApprovalOutcomesByDatasource(
                organizationId, datasourceId, since));
    }

    // Index map for findApprovalOutcomeSampleRows — must match the repository's select list:
    // 0 q.id, 1 q.queryType, 2 q.transactional, 3 q.createdAt, 4 submitter id, 5 datasource id,
    // 6 q.status, 7 ai.riskScore, 8 ai.riskLevel, 9 ai.issues, 10 ai.failed,
    // 11 est.estimatedRows, 12 est.affectedRowCount, 13 est.estimatedCost, 14 est.scanType,
    // 15 est.supported, 16 est.failed
    private ApprovalOutcomeSample toSample(Object[] row) {
        var status = (QueryStatus) row[6];
        var aiMissing = row[10] == null || (Boolean) row[10];
        var estimateMissing = row[15] == null || !((Boolean) row[15]) || (Boolean) row[16];
        return new ApprovalOutcomeSample(
                (UUID) row[0],
                (QueryType) row[1],
                (Boolean) row[2],
                (Instant) row[3],
                (UUID) row[4],
                (UUID) row[5],
                aiMissing ? null : (Integer) row[7],
                aiMissing ? null : (RiskLevel) row[8],
                aiMissing ? null : countIssues((String) row[9]),
                aiMissing,
                estimateMissing ? null : (Long) row[11],
                estimateMissing ? null : (Long) row[12],
                estimateMissing ? null : (Double) row[13],
                estimateMissing ? null : (String) row[14],
                estimateMissing,
                status == QueryStatus.APPROVED || status == QueryStatus.EXECUTED);
    }

    private int countIssues(String issuesJson) {
        if (issuesJson == null) {
            return 0;
        }
        try {
            return objectMapper.readTree(issuesJson).size();
        } catch (RuntimeException e) {
            log.debug("Unparseable ai_analyses.issues JSON, counting as 0 issues", e);
            return 0;
        }
    }

    private ApprovalRateCounts toCounts(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return new ApprovalRateCounts(0, 0);
        }
        var row = rows.get(0);
        return new ApprovalRateCounts(((Number) row[0]).longValue(),
                ((Number) row[1]).longValue());
    }
}
