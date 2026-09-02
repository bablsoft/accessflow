import { useState } from 'react';
import { Drawer, Pagination, Skeleton, Tag, Timeline, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { apiErrorMessage } from '@/utils/apiErrors';
import { StatusPill } from '@/components/common/StatusPill';
import {
  deploymentVersionKeys,
  listDeploymentEnvironmentHistory,
} from '@/api/deploymentVersions';
import { deploymentOutcomeLabel, submissionReasonLabel } from '@/utils/enumLabels';
import { deploymentOutcomeColor, statusColor } from '@/utils/statusColors';
import { fmtDate } from '@/utils/dateFormat';
import { usePermission } from '@/utils/permissions';
import { useAuthStore } from '@/store/authStore';
import type {
  DeploymentVersionEnvironmentRef,
  DeploymentVersionHistoryEntry,
} from '@/types/api';

const PAGE_SIZE = 20;

function HistoryBody({
  pipelineId,
  environmentId,
}: {
  pipelineId: string;
  environmentId: string;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const user = useAuthStore((s) => s.user);
  const canReview = usePermission('DEPLOYMENT_REVIEW');
  const isAdmin = usePermission('QUERY_ADMIN');

  const filters = { page, size: PAGE_SIZE };
  const historyQuery = useQuery({
    queryKey: deploymentVersionKeys.history(pipelineId, environmentId, filters),
    queryFn: () => listDeploymentEnvironmentHistory(pipelineId, environmentId, filters),
  });

  if (historyQuery.isLoading) return <Skeleton active paragraph={{ rows: 6 }} />;
  // Never fall through to the empty state on a failure — "no deployments yet" is a positive
  // claim about governance data, and a 500 must not be able to make it.
  if (historyQuery.isError) {
    return (
      <EmptyState
        title={t('deploygov.error')}
        description={apiErrorMessage(historyQuery.error, () => t('deploygov.error'))}
        size="sm"
      />
    );
  }

  const entries = historyQuery.data?.content ?? [];
  if (entries.length === 0) {
    return <EmptyState title={t('deploygov.versions.historyEmpty')} size="sm" />;
  }

  // The history endpoint deliberately admits DEPLOYMENT_PIPELINE_MANAGE and can_trigger holders,
  // but GET /deployment-requests/{id} is narrower — submitter, DEPLOYMENT_REVIEW or QUERY_ADMIN
  // (docs/04-api-spec.md → "History entry"). A manage-only admin therefore reads this timeline and
  // would 404 on the drill-down, so the link is rendered only when it will actually resolve.
  const canOpen = (entry: DeploymentVersionHistoryEntry) =>
    canReview || isAdmin || entry.submitted_by === user?.id;

  const total = historyQuery.data?.total_elements ?? 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Timeline
        items={entries.map((entry) => {
          const outcomeColor =
            entry.outcome == null ? null : deploymentOutcomeColor(entry.outcome);
          const deployed = entry.executed_at ?? entry.created_at;
          const deployedLabel =
            entry.executed_at != null
              ? t('deploygov.versions.historyExecutedAt')
              : t('deploygov.versions.historySubmittedAt');
          return {
            key: entry.request_id,
            color: statusColor(entry.status).fg,
            children: (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                  <span className="mono" style={{ fontSize: 13, fontWeight: 600 }}>
                    {entry.version}
                  </span>
                  <StatusPill status={entry.status} size="sm" />
                  {entry.outcome != null && outcomeColor != null && (
                    <Tag
                      style={{
                        color: outcomeColor.fg,
                        background: outcomeColor.bg,
                        borderColor: outcomeColor.border,
                      }}
                    >
                      {deploymentOutcomeLabel(t, entry.outcome)}
                    </Tag>
                  )}
                </div>
                <span className="muted" style={{ fontSize: 12 }}>
                  {deployedLabel}: {fmtDate(deployed)}
                </span>
                <span className="muted" style={{ fontSize: 12 }}>
                  {submissionReasonLabel(t, entry.submission_reason)}
                </span>
                {entry.commit_sha != null && (
                  <span className="muted mono" style={{ fontSize: 11 }}>
                    {t('deploygov.versions.historyCommit')}: {entry.commit_sha.slice(0, 7)}
                  </span>
                )}
                <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                  {entry.run_url != null && (
                    <a
                      href={entry.run_url}
                      target="_blank"
                      rel="noreferrer"
                      style={{ fontSize: 12 }}
                    >
                      {t('deploygov.versions.historyRun')}
                    </a>
                  )}
                  {canOpen(entry) ? (
                    <Link to={`/deployments/${entry.request_id}`} style={{ fontSize: 12 }}>
                      {t('deploygov.versions.openRequest')}
                    </Link>
                  ) : (
                    <Tooltip title={t('deploygov.versions.noDrilldown')}>
                      <span className="muted" style={{ fontSize: 12 }}>
                        {t('deploygov.versions.openRequest')}
                      </span>
                    </Tooltip>
                  )}
                </div>
              </div>
            ),
          };
        })}
      />
      {total > PAGE_SIZE && (
        <Pagination
          simple
          current={page + 1}
          pageSize={PAGE_SIZE}
          total={total}
          onChange={(p) => setPage(p - 1)}
        />
      )}
    </div>
  );
}

export function EnvironmentHistoryDrawer({
  pipelineId,
  environment,
  onClose,
}: {
  pipelineId: string;
  environment: DeploymentVersionEnvironmentRef | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Drawer
      open={environment !== null}
      onClose={onClose}
      size={520}
      destroyOnHidden
      title={
        environment === null
          ? t('deploygov.versions.history')
          : t('deploygov.versions.historyTitle', { name: environment.name })
      }
    >
      {environment !== null && (
        // Keyed so switching environments resets the page cursor without a useEffect.
        <HistoryBody
          key={environment.id}
          pipelineId={pipelineId}
          environmentId={environment.id}
        />
      )}
    </Drawer>
  );
}
