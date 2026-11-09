import { useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import {
  acceptApiSchemaRevision,
  apiConnectorKeys,
  dismissApiDriftFinding,
  listApiDriftFindings,
  listApiSchemaRevisions,
  listApiSchemas,
  refreshApiSchema,
} from '@/api/apiConnectors';
import { Pill } from '@/components/common/Pill';
import {
  API_DRIFT_FINDING_STATUSES,
  apiDriftFindingKindLabel,
  apiDriftFindingStatusLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { apiDriftFindingKindColor } from '@/utils/statusColors';
import { fmtDate } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  ApiDriftFinding,
  ApiDriftFindingKind,
  ApiDriftFindingStatus,
  ApiOperation,
  ApiSchema,
  ApiSchemaRevision,
} from '@/types/api';

const HASH_PREFIX = 12;

/** A failed read must never render as the reassuring empty state ("no drift"). */
function LoadError({ error }: { error: unknown }) {
  const { t } = useTranslation();
  return <Alert type="error" showIcon title={apiErrorMessage(error, () => t('apiGov.error'))} />;
}

function operationText(t: TFunction, op: ApiOperation): string {
  const access = op.write ? t('apiGov.settings.write') : t('apiGov.settings.read');
  const deprecated = op.deprecated ? ` · ${t('apiGov.drift.deprecated')}` : '';
  return `${op.verb} ${op.path} (${access}${deprecated})`;
}

function ChangeSummary({ finding }: { finding: ApiDriftFinding }) {
  const { t } = useTranslation();
  const before = finding.before ? operationText(t, finding.before) : null;
  const after = finding.after ? operationText(t, finding.after) : null;
  if (before && after) {
    return (
      <Typography.Text>
        {before} → {after}
      </Typography.Text>
    );
  }
  return <Typography.Text>{after ?? before ?? '—'}</Typography.Text>;
}

/**
 * Drift review (#896). Findings are advisory: dismissing one changes nothing, and the operation
 * catalog only moves when an admin explicitly accepts a revision.
 */
export function ApiConnectorDriftTab({
  connectorId,
  onOpenPermissions,
}: {
  connectorId: string;
  onOpenPermissions: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<ApiDriftFindingStatus>('OPEN');
  const findingsQuery = useQuery({
    queryKey: apiConnectorKeys.findings(connectorId, status),
    queryFn: () => listApiDriftFindings(connectorId, status),
  });
  const schemasQuery = useQuery({
    queryKey: apiConnectorKeys.schemas(connectorId),
    queryFn: () => listApiSchemas(connectorId),
  });
  const dismissMutation = useMutation({
    mutationFn: (findingId: string) => dismissApiDriftFinding(connectorId, findingId),
    onSuccess: () => {
      message.success(t('apiGov.drift.dismissed'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.findings(connectorId) });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const refreshable = (schemasQuery.data ?? []).filter((s) => !!s.source_url);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <Alert type="info" showIcon title={t('apiGov.drift.explainer')} />
      <section>
        <Space style={{ marginBottom: 12, justifyContent: 'space-between', width: '100%' }}>
          <Typography.Title level={5} style={{ margin: 0 }}>
            {t('apiGov.drift.findingsTitle')}
          </Typography.Title>
          <Select<ApiDriftFindingStatus>
            style={{ width: 180 }}
            value={status}
            onChange={setStatus}
            options={enumOptions(API_DRIFT_FINDING_STATUSES, apiDriftFindingStatusLabel, t)}
            aria-label={t('apiGov.drift.statusFilter')}
          />
        </Space>
        {findingsQuery.isError ? (
          <LoadError error={findingsQuery.error} />
        ) : (
          <Table<ApiDriftFinding>
            rowKey="id"
            size="small"
            pagination={false}
            loading={findingsQuery.isLoading}
            dataSource={findingsQuery.data ?? []}
            locale={{ emptyText: t('apiGov.drift.noFindings') }}
            columns={[
              {
                title: t('apiGov.drift.kind'),
                dataIndex: 'kind',
                render: (kind: ApiDriftFindingKind) => {
                  const c = apiDriftFindingKindColor(kind);
                  return (
                    <Pill fg={c.fg} bg={c.bg} border={c.border} size="sm">
                      {apiDriftFindingKindLabel(t, kind)}
                    </Pill>
                  );
                },
              },
              { title: t('apiGov.settings.operationId'), dataIndex: 'operation_id' },
              {
                title: t('apiGov.drift.change'),
                key: 'change',
                render: (_: unknown, row: ApiDriftFinding) => <ChangeSummary finding={row} />,
              },
              {
                title: t('apiGov.drift.affectedGrants'),
                dataIndex: 'affected_grant_count',
                render: (count: number) =>
                  count > 0 ? (
                    <Button type="link" size="small" style={{ padding: 0 }} onClick={onOpenPermissions}>
                      {t('apiGov.drift.affectedGrantsLink', { count })}
                    </Button>
                  ) : (
                    '—'
                  ),
              },
              {
                title: t('apiGov.drift.detectedAt'),
                dataIndex: 'created_at',
                render: (v: string) => fmtDate(v),
              },
              {
                title: '',
                key: 'actions',
                render: (_: unknown, row: ApiDriftFinding) =>
                  row.status === 'OPEN' ? (
                    <Popconfirm
                      title={t('apiGov.drift.dismissConfirm')}
                      okText={t('apiGov.drift.dismiss')}
                      cancelText={t('common.cancel')}
                      onConfirm={() => dismissMutation.mutate(row.id)}
                    >
                      <Button
                        size="small"
                        loading={dismissMutation.isPending && dismissMutation.variables === row.id}
                      >
                        {t('apiGov.drift.dismiss')}
                      </Button>
                    </Popconfirm>
                  ) : (
                    <Tag>{apiDriftFindingStatusLabel(t, row.status)}</Tag>
                  ),
              },
            ]}
          />
        )}
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          {t('apiGov.drift.grantImpactHint')}
        </Typography.Text>
      </section>
      <section style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {t('apiGov.drift.revisionsTitle')}
        </Typography.Title>
        {schemasQuery.isError ? (
          <LoadError error={schemasQuery.error} />
        ) : !schemasQuery.isLoading && refreshable.length === 0 ? (
          <Empty description={t('apiGov.drift.noRefreshableSchema')} />
        ) : (
          refreshable.map((schema) => (
            <SchemaRevisionsCard key={schema.id} connectorId={connectorId} schema={schema} />
          ))
        )}
      </section>
    </div>
  );
}

function SchemaRevisionsCard({ connectorId, schema }: { connectorId: string; schema: ApiSchema }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const revisionsQuery = useQuery({
    queryKey: apiConnectorKeys.revisions(connectorId, schema.id),
    queryFn: () => listApiSchemaRevisions(connectorId, schema.id),
  });
  const openFindingsQuery = useQuery({
    queryKey: apiConnectorKeys.findings(connectorId, 'OPEN'),
    queryFn: () => listApiDriftFindings(connectorId, 'OPEN'),
  });
  const invalidate = () => {
    // schemas(id) is the prefix of revisions(id, schemaId), so this re-reads both.
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.schemas(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.findings(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.operations(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
  };
  const refreshMutation = useMutation({
    mutationFn: () => refreshApiSchema(connectorId, schema.id),
    onSuccess: (result) => {
      if (result.unchanged) {
        message.info(t('apiGov.drift.refreshUnchanged'));
      } else {
        message.success(t('apiGov.drift.refreshFindings', { count: result.finding_count }));
      }
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const acceptMutation = useMutation({
    mutationFn: (revisionId: string) => acceptApiSchemaRevision(connectorId, schema.id, revisionId),
    onSuccess: () => {
      message.success(t('apiGov.drift.accepted'));
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const revisions = revisionsQuery.data ?? [];
  const current = revisions.find((r) => r.id === schema.current_revision_id);

  const confirmAccept = (revision: ApiSchemaRevision) => {
    const counts = new Map<ApiDriftFindingKind, number>();
    for (const f of openFindingsQuery.data ?? []) {
      if (f.revision_id === revision.id) counts.set(f.kind, (counts.get(f.kind) ?? 0) + 1);
    }
    modal.confirm({
      title: t('apiGov.drift.acceptConfirmTitle'),
      okText: t('apiGov.drift.accept'),
      cancelText: t('common.cancel'),
      content: (
        <div>
          <Typography.Paragraph>
            {t('apiGov.drift.acceptConfirmOperations', {
              from: current?.operation_count ?? schema.total_operation_count,
              to: revision.operation_count,
            })}
          </Typography.Paragraph>
          {counts.size > 0 && (
            <ul>
              {[...counts.entries()].map(([kind, count]) => (
                <li key={kind}>
                  {apiDriftFindingKindLabel(t, kind)}: {count}
                </li>
              ))}
            </ul>
          )}
          <Typography.Paragraph type="secondary">
            {t('apiGov.drift.acceptConfirmBody')}
          </Typography.Paragraph>
        </div>
      ),
      onOk: () => acceptMutation.mutateAsync(revision.id),
    });
  };

  return (
    <Card
      size="small"
      title={
        <Typography.Text ellipsis style={{ maxWidth: 520 }}>
          {schema.source_url}
        </Typography.Text>
      }
      extra={
        <Button loading={refreshMutation.isPending} onClick={() => refreshMutation.mutate()}>
          {t('apiGov.drift.refreshNow')}
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        {schema.refresh_interval_minutes != null
          ? t('apiGov.settings.autoRefreshEvery', { minutes: schema.refresh_interval_minutes })
          : t('apiGov.settings.autoRefreshOff')}
        {schema.last_refresh_at &&
          ` · ${t('apiGov.settings.lastRefreshed', { time: fmtDate(schema.last_refresh_at) })}`}
      </Typography.Paragraph>
      {schema.last_refresh_error && (
        <Alert type="error" showIcon style={{ marginBottom: 8 }} title={schema.last_refresh_error} />
      )}
      {revisionsQuery.isError ? (
        <LoadError error={revisionsQuery.error} />
      ) : (
        <Table<ApiSchemaRevision>
          rowKey="id"
          size="small"
          pagination={false}
          loading={revisionsQuery.isLoading}
          dataSource={revisions}
          locale={{ emptyText: t('apiGov.drift.noRevisions') }}
          columns={[
            {
              title: t('apiGov.drift.fetchedAt'),
              dataIndex: 'fetched_at',
              render: (v: string) => fmtDate(v),
            },
            {
              title: t('apiGov.drift.lastSeenAt'),
              dataIndex: 'last_seen_at',
              render: (v: string) => fmtDate(v),
            },
            { title: t('apiGov.settings.operations'), dataIndex: 'operation_count' },
            {
              title: t('apiGov.drift.contentHash'),
              dataIndex: 'content_hash',
              render: (v: string) => <Typography.Text code>{v.slice(0, HASH_PREFIX)}</Typography.Text>,
            },
            {
              title: t('apiGov.drift.revisionState'),
              key: 'state',
              render: (_: unknown, row: ApiSchemaRevision) => (
                <Space size={4}>
                  {row.id === schema.current_revision_id && (
                    <Tag color="blue">{t('apiGov.drift.current')}</Tag>
                )}
                {row.accepted_at != null ? (
                  <Tag color="green">{t('apiGov.drift.acceptedOn', { time: fmtDate(row.accepted_at) })}</Tag>
                ) : (
                  <Tag color="orange">{t('apiGov.drift.pending')}</Tag>
                )}
              </Space>
            ),
          },
          {
            title: '',
            key: 'actions',
            // Only the governed revision counts as accepted (backend 409); an older revision
            // accepted before a rollback can be accepted again.
            render: (_: unknown, row: ApiSchemaRevision) =>
              row.id !== schema.current_revision_id ? (
                <Button
                  size="small"
                  type="primary"
                  loading={acceptMutation.isPending && acceptMutation.variables === row.id}
                  onClick={() => confirmAccept(row)}
                >
                  {t('apiGov.drift.accept')}
                </Button>
              ) : null,
          },
        ]}
      />
      )}
    </Card>
  );
}
