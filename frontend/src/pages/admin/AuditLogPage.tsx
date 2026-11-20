import { useMemo, useState } from 'react';
import { Alert, Button, DatePicker, Drawer, Input, Select, Skeleton, Space, message } from 'antd';
import type { Dayjs } from 'dayjs';
import {
  DownloadOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DataTable, FilterBar, Page, PageBody } from '@/components/layout';
import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Avatar } from '@/components/common/Avatar';
import { OnBehalfOfTag } from '@/components/common/OnBehalfOfTag';
import { ClientApplicationTag } from '@/components/common/ClientApplicationTag';
import {
  auditKeys,
  exportAuditLogCsv,
  listAuditEvents,
  verifyAuditChain,
  type AuditLogExportResult,
} from '@/api/admin';
import { adminErrorMessage, apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import type { AuditChainResult, AuditEvent, AuditLogFilters } from '@/types/api';
import { downloadBlob } from '@/utils/downloadBlob';

const PAGE_SIZE = 20;

const RESOURCE_TYPES = [
  'query_request',
  'datasource',
  'user',
  'permission',
  'review_plan',
  'notification_channel',
  'audit_sink',
  'api_connector',
  'deployment_pipeline',
  'deployment_request',
  'break_glass_event',
  'service_account',
];

const ACTIONS = [
  'QUERY_SUBMITTED',
  'QUERY_AI_ANALYZED',
  'QUERY_AI_FAILED',
  'QUERY_REVIEW_REQUESTED',
  'QUERY_APPROVED',
  'QUERY_REJECTED',
  'QUERY_EXECUTED',
  'QUERY_FAILED',
  'QUERY_CANCELLED',
  'DATASOURCE_CREATED',
  'DATASOURCE_UPDATED',
  'PERMISSION_GRANTED',
  'PERMISSION_REVOKED',
  'PERMISSION_GROUP_GRANTED',
  'PERMISSION_GROUP_REVOKED',
  'REVIEW_PLAN_CREATED',
  'REVIEW_PLAN_UPDATED',
  'REVIEW_PLAN_DELETED',
  'USER_LOGIN',
  'USER_LOGIN_FAILED',
  'USER_CREATED',
  'USER_DEACTIVATED',
  'RESULT_EXPORTED',
  'EXPORT_POLICY_CREATED',
  'EXPORT_POLICY_UPDATED',
  'EXPORT_POLICY_DELETED',
  'ROW_LIMIT_POLICY_CREATED',
  'ROW_LIMIT_POLICY_UPDATED',
  'ROW_LIMIT_POLICY_DELETED',
  'DATA_BUDGET_CREATED',
  'DATA_BUDGET_UPDATED',
  'DATA_BUDGET_DELETED',
  'QUERY_DATA_BUDGET_ENFORCED',
  'AUDIT_SINK_CREATED',
  'AUDIT_SINK_UPDATED',
  'AUDIT_SINK_DELETED',
  // #695 — deployment governance lifecycle (epic AF-682).
  'DEPLOYMENT_SUBMITTED',
  'DEPLOYMENT_APPROVED',
  'DEPLOYMENT_REJECTED',
  'DEPLOYMENT_TIMED_OUT',
  'DEPLOYMENT_REVIEW_ESCALATED',
  'DEPLOYMENT_REVIEW_NUDGED',
  'DEPLOYMENT_CANCELLED',
  'DEPLOYMENT_EXECUTED',
  'DEPLOYMENT_OUTCOME_REPORTED',
  'DEPLOYMENT_VERIFICATION_FAILED',
  'DEPLOYMENT_BREAK_GLASS_EXECUTED',
  'DEPLOYMENT_BREAK_GLASS_REVIEWED',
  'DEPLOYMENT_ROLLBACK_REVIEWED',
  'API_BREAK_GLASS_REVIEWED',
  'ACCESS_SIMULATION_RUN',
  'PRIVILEGED_ACCESS_REPORT_VIEWED',
  'EFFECTIVE_PERMISSION_VIEWED',
  'SERVICE_ACCOUNT_CREATED',
  'SERVICE_ACCOUNT_UPDATED',
  'SERVICE_ACCOUNT_DEACTIVATED',
  'SERVICE_ACCOUNT_KEY_ISSUED',
  'SERVICE_ACCOUNT_KEY_ROTATED',
  'SERVICE_ACCOUNT_KEY_REVOKED',
  'SERVICE_ACCOUNT_DELEGATION_GRANTED',
  'SERVICE_ACCOUNT_DELEGATION_REVOKED',
];

const actionColor = (a: string): string => {
  if (
    a.includes('REJECTED') ||
    a.includes('FAILED') ||
    a.includes('DEACTIVATED') ||
    a.includes('REVOKED')
  ) {
    return 'var(--risk-crit)';
  }
  if (a.includes('APPROVED') || a.includes('EXECUTED') || a.includes('CREATED')) {
    return 'var(--risk-low)';
  }
  if (a.includes('LOGIN_FAILED')) return 'var(--risk-high)';
  return 'var(--fg)';
};

export function AuditLogPage() {
  const { t } = useTranslation();
  // Deep links pre-filter the log: a service account's Activity tab opens ?actor_id=<agent>, and
  // an on-behalf-of chip can open ?on_behalf_of_user_id=<person> (#875). Read once, then local.
  const [searchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [action, setAction] = useState<string>('all');
  const [resourceType, setResourceType] = useState<string>('all');
  const [actorId, setActorId] = useState(() => searchParams.get('actor_id') ?? '');
  const [onBehalfOfUserId, setOnBehalfOfUserId] = useState(
    () => searchParams.get('on_behalf_of_user_id') ?? '',
  );
  const [applicationName, setApplicationName] = useState(
    () => searchParams.get('application_name') ?? '',
  );
  const [resourceId, setResourceId] = useState('');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [detail, setDetail] = useState<AuditEvent | null>(null);
  const [chainResult, setChainResult] = useState<AuditChainResult | null>(null);
  const [chainError, setChainError] = useState<string | null>(null);

  const verifyChain = useMutation({
    mutationFn: () => verifyAuditChain(),
    onMutate: () => {
      setChainResult(null);
      setChainError(null);
    },
    onSuccess: (result) => {
      setChainResult(result);
    },
    onError: (err) => {
      setChainError(adminErrorMessage(err));
    },
  });

  const filters: AuditLogFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      sort: 'createdAt,DESC',
      action: action === 'all' ? undefined : action,
      resource_type: resourceType === 'all' ? undefined : resourceType,
      actor_id: actorId.trim() || undefined,
      on_behalf_of_user_id: onBehalfOfUserId.trim() || undefined,
      application_name: applicationName.trim() || undefined,
      resource_id: resourceId.trim() || undefined,
      from: range?.[0]?.toISOString(),
      to: range?.[1]?.toISOString(),
    }),
    [page, action, resourceType, actorId, onBehalfOfUserId, applicationName, resourceId, range],
  );

  const exportCsv = useMutation({
    mutationFn: () => exportAuditLogCsv(filters),
    onSuccess: (result: AuditLogExportResult) => {
      downloadBlob(result);
      if (result.truncated) {
        message.warning(t('admin.audit.export_truncated'));
      }
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('admin.audit.export_failed')));
    },
  });

  const auditQuery = useQuery({
    queryKey: auditKeys.list(filters),
    queryFn: () => listAuditEvents(filters),
  });

  const events = auditQuery.data?.content ?? [];

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-audit-log"
        title={t('admin.audit.title')}
        subtitle={t('admin.audit.subtitle')}
        actions={
          <Space>
            <Button
              icon={<DownloadOutlined />}
              loading={exportCsv.isPending}
              onClick={() => exportCsv.mutate()}
              data-testid="export-csv-button"
            >
              {t('admin.audit.export_csv')}
            </Button>
            <Button
              icon={<SafetyCertificateOutlined />}
              loading={verifyChain.isPending}
              onClick={() => verifyChain.mutate()}
              data-testid="verify-chain-button"
            >
              {t('admin.audit.verify_chain')}
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => auditQuery.refetch()}>
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <FilterBar>
        <Select
          value={action}
          onChange={(v) => {
            setAction(v);
            setPage(0);
          }}
          className="u-w-md"
          showSearch={{ optionFilterProp: 'label' }}
          options={[
            { value: 'all', label: t('admin.audit.filter_all_actions') },
            ...ACTIONS.map((a) => ({ value: a, label: a })),
          ]}
        />
        <Select
          value={resourceType}
          onChange={(v) => {
            setResourceType(v);
            setPage(0);
          }}
          className="u-w-md"
          showSearch={{ optionFilterProp: 'label' }}
          options={[
            { value: 'all', label: t('admin.audit.filter_all_resources') },
            ...RESOURCE_TYPES.map((r) => ({ value: r, label: r })),
          ]}
        />
        <Input
          placeholder={t('admin.audit.filter_actor_placeholder')}
          value={actorId}
          onChange={(e) => {
            setActorId(e.target.value);
            setPage(0);
          }}
          className="mono u-w-lg"
        />
        <Input
          placeholder={t('admin.audit.filter_on_behalf_of_placeholder')}
          aria-label={t('admin.audit.filter_on_behalf_of_placeholder')}
          value={onBehalfOfUserId}
          onChange={(e) => {
            setOnBehalfOfUserId(e.target.value);
            setPage(0);
          }}
          className="mono u-w-lg"
        />
        <Input
          placeholder={t('client_application.filter_placeholder')}
          aria-label={t('client_application.filter_aria')}
          value={applicationName}
          onChange={(e) => {
            setApplicationName(e.target.value);
            setPage(0);
          }}
          className="mono u-w-md"
        />
        <Input
          placeholder={t('admin.audit.filter_resource_id_placeholder')}
          value={resourceId}
          onChange={(e) => {
            setResourceId(e.target.value);
            setPage(0);
          }}
          className="mono u-w-lg"
        />
        <DatePicker.RangePicker
          showTime
          value={range as [Dayjs | null, Dayjs | null]}
          onChange={(v) => {
            setRange(v as [Dayjs | null, Dayjs | null] | null);
            setPage(0);
          }}
        />
        <div className="u-flex-1" />
        <span className="mono muted u-text-2xs u-self-center">
          {t('admin.audit.count_label', {
            count: auditQuery.data?.total_elements ?? 0,
          })}
        </span>
      </FilterBar>
      {(chainResult || chainError) && (
        <div style={{ padding: '12px var(--gutter) 0' }}>
          {chainError ? (
            <Alert
              type="error"
              showIcon
              closable={{ closeIcon: true, onClose: () => setChainError(null) }}
              title={t('admin.audit.chain_invalid')}
              description={chainError}
              data-testid="verify-chain-result"
            />
          ) : chainResult?.ok ? (
            <Alert
              type="success"
              showIcon
              closable={{ closeIcon: true, onClose: () => setChainResult(null) }}
              title={t('admin.audit.chain_valid')}
              description={t('admin.audit.chain_rows_checked', {
                count: chainResult.rows_checked,
              })}
              data-testid="verify-chain-result"
            />
          ) : chainResult ? (
            <Alert
              type="error"
              showIcon
              closable={{ closeIcon: true, onClose: () => setChainResult(null) }}
              title={t('admin.audit.chain_invalid')}
              description={t('admin.audit.chain_invalid_detail', {
                rowId: chainResult.first_bad_row_id ?? '—',
                reason: chainResult.first_bad_reason ?? '—',
              })}
              data-testid="verify-chain-result"
            />
          ) : null}
        </div>
      )}
      <PageBody flush>
        {auditQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} className="u-p-6" />
        ) : auditQuery.isError ? (
          <EmptyState
            title={t('admin.audit.load_error')}
            description={adminErrorMessage(auditQuery.error)}
          />
        ) : events.length === 0 ? (
          <EmptyState title={t('admin.audit.title')} description={t('admin.audit.empty')} />
        ) : (
          <DataTable<AuditEvent>
            rowKey="id"
            size="middle"
            dataSource={events}
            scroll={{ x: 'max-content' }}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: auditQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            onRowActivate={(record) => setDetail(record)}
            columns={[
              {
                title: t('admin.audit.col_when'),
                dataIndex: 'created_at',
                width: 120,
                render: (v) => <span className="muted">{timeAgo(v)}</span>,
              },
              {
                title: t('admin.audit.col_actor'),
                width: 220,
                render: (_v, e) => {
                  const name = userDisplay(e.actor_display_name, e.actor_email)
                    || t('admin.audit.actor_unknown');
                  const onBehalfOfId = e.metadata.on_behalf_of_user_id;
                  return (
                    <div className="u-flex u-items-center u-gap-2">
                      <Avatar name={name} size={20} />
                      <div>
                        <div className="u-text-xs">{name}</div>
                        {e.actor_email && (
                          <div className="mono muted u-text-2xs">
                            {e.actor_email}
                          </div>
                        )}
                        {e.metadata.service_account === true && (
                          <div className="muted u-text-2xs">
                            {t('admin.audit.via_service_account')}
                          </div>
                        )}
                        <OnBehalfOfTag
                          email={e.on_behalf_of_email}
                          userId={typeof onBehalfOfId === 'string' ? onBehalfOfId : null}
                        />
                        {typeof e.metadata.application_name === 'string' && (
                          <div className="u-text-2xs">
                            <ClientApplicationTag
                              name={e.metadata.application_name}
                              source={
                                typeof e.metadata.application_name_source === 'string'
                                  ? e.metadata.application_name_source
                                  : null
                              }
                              testId={`audit-application-${e.id}`}
                            />
                          </div>
                        )}
                      </div>
                    </div>
                  );
                },
              },
              {
                title: t('admin.audit.col_action'),
                dataIndex: 'action',
                width: 230,
                render: (v) => (
                  <span
                    className="mono u-text-xs u-fw-500"
                    style={{ color: actionColor(v) }}
                  >
                    {v}
                  </span>
                ),
              },
              {
                title: t('admin.audit.col_resource'),
                dataIndex: 'resource_type',
                width: 150,
                render: (v) => (
                  <span className="mono u-text-xs">
                    {v}
                  </span>
                ),
              },
              {
                title: t('admin.audit.col_resource_id'),
                dataIndex: 'resource_id',
                render: (v: string | null) =>
                  v ? (
                    <span className="mono muted u-text-xs">
                      {v}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  ),
              },
              {
                title: t('admin.audit.col_ip'),
                dataIndex: 'ip_address',
                width: 130,
                render: (v: string | null) =>
                  v ? (
                    <span className="mono muted u-text-xs">
                      {v}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  ),
              },
            ]}
          />
        )}
      </PageBody>
      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        title={t('admin.audit.drawer_title')}
        size={520}
      >
        {detail && (
          <div className="u-flex u-col u-gap-4">
            <Card>
              <div
                className="muted u-text-2xs u-uppercase u-tracking-wide u-mb-2"
              >
                {t('admin.audit.section_action')}
              </div>
              <div
                className="mono u-text-md u-fw-600"
                style={{ color: actionColor(detail.action) }}
              >
                {detail.action}
              </div>
            </Card>
            <Card>
              <div className="u-flex u-col u-gap-2">
                <Row k="audit.id" v={detail.id} />
                <Row k="organization_id" v={detail.organization_id} />
                <Row k="actor.id" v={detail.actor_id ?? '—'} />
                <Row k="actor.email" v={detail.actor_email ?? '—'} />
                <Row k="actor.display_name" v={detail.actor_display_name ?? '—'} />
                <Row k="on_behalf_of.email" v={detail.on_behalf_of_email ?? '—'} />
                <div
                  className="u-flex u-justify-between u-gap-2 u-mono u-text-xs"
                >
                  <span className="muted">application</span>
                  {typeof detail.metadata.application_name === 'string' ? (
                    <ClientApplicationTag
                      name={detail.metadata.application_name}
                      source={
                        typeof detail.metadata.application_name_source === 'string'
                          ? detail.metadata.application_name_source
                          : null
                      }
                      testId="audit-detail-application"
                    />
                  ) : (
                    <span>—</span>
                  )}
                </div>
                <Row k="resource.type" v={detail.resource_type} />
                <Row k="resource.id" v={detail.resource_id ?? '—'} />
                <Row k="ip" v={detail.ip_address ?? '—'} />
                <Row k="user_agent" v={detail.user_agent ?? '—'} />
                <Row k="created_at" v={fmtDate(detail.created_at)} />
              </div>
            </Card>
            <Card title={t('admin.audit.section_metadata')}>
              <pre
                className="u-m-0 u-mono u-text-xs u-leading-normal u-bg-code u-p-4" style={{ borderRadius: '0 0 var(--radius-md) var(--radius-md)' }}
              >
                {JSON.stringify(detail.metadata ?? {}, null, 2)}
              </pre>
            </Card>
          </div>
        )}
      </Drawer>
    </Page>
  );
}

function Card({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <div
      className="af-box"
    >
      {title && (
        <div
          className="u-fw-600 u-text-xs u-border-b u-py-2 u-px-4"
        >
          {title}
        </div>
      )}
      {!title ? <div className="u-p-4">{children}</div> : children}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div
      className="u-flex u-justify-between u-gap-2 u-mono u-text-xs"
    >
      <span className="muted">{k}</span>
      <span className="u-text-right">{v}</span>
    </div>
  );
}
