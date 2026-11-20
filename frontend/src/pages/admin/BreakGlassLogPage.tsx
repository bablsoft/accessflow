import { useMemo, useState } from 'react';
import { App, Button, DatePicker, Drawer, Input, Modal, Select, Skeleton, Space, Tooltip } from 'antd';
import type { Dayjs } from 'dayjs';
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DataTable, FilterBar, Page, PageBody } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Avatar } from '@/components/common/Avatar';
import { BreakGlassStatusPill } from '@/components/common/BreakGlassStatusPill';
import {
  acknowledgeBreakGlassEvent,
  breakGlassKeys,
  listBreakGlassEvents,
} from '@/api/breakGlass';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { useAuthStore } from '@/store/authStore';
import { adminErrorMessage } from '@/utils/apiErrors';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import { BREAK_GLASS_STATUSES, breakGlassStatusLabel, enumOptions } from '@/utils/enumLabels';
import type {
  BreakGlassEvent,
  BreakGlassEventStatus,
  BreakGlassListFilters,
} from '@/types/api';

const PAGE_SIZE = 20;

export default function BreakGlassLogPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const currentUserId = useAuthStore((s) => s.user?.id);

  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<BreakGlassEventStatus | 'all'>('PENDING_REVIEW');
  const [datasourceId, setDatasourceId] = useState<string>('all');
  const [userId, setUserId] = useState('');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [detail, setDetail] = useState<BreakGlassEvent | null>(null);
  const [ackTarget, setAckTarget] = useState<BreakGlassEvent | null>(null);
  const [ackComment, setAckComment] = useState('');

  const filters: BreakGlassListFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      status: status === 'all' ? undefined : status,
      datasource_id: datasourceId === 'all' ? undefined : datasourceId,
      user_id: userId.trim() || undefined,
      from: range?.[0]?.toISOString(),
      to: range?.[1]?.toISOString(),
    }),
    [page, status, datasourceId, userId, range],
  );

  const eventsQuery = useQuery({
    queryKey: breakGlassKeys.list(filters),
    queryFn: () => listBreakGlassEvents(filters),
  });

  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ page: 0, size: 100 }),
    queryFn: () => listDatasources({ page: 0, size: 100 }),
  });

  const acknowledge = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment: string }) =>
      acknowledgeBreakGlassEvent(id, comment.trim() || undefined),
    onSuccess: () => {
      message.success(t('breakglass.acknowledge_success'));
      void queryClient.invalidateQueries({ queryKey: breakGlassKeys.all });
      closeAck();
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const closeAck = () => {
    setAckTarget(null);
    setAckComment('');
  };

  const events = eventsQuery.data?.content ?? [];

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-break-glass"
        title={t('breakglass.title')}
        subtitle={t('breakglass.subtitle')}
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => eventsQuery.refetch()}>
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <FilterBar>
        <Select
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          style={{ width: 200 }}
          data-testid="breakglass-status"
          options={[
            { value: 'all', label: t('breakglass.filter_status_all') },
            ...enumOptions(BREAK_GLASS_STATUSES, breakGlassStatusLabel, t),
          ]}
        />
        <Select
          value={datasourceId}
          onChange={(v) => {
            setDatasourceId(v);
            setPage(0);
          }}
          style={{ width: 240 }}
          showSearch={{ optionFilterProp: 'label' }}
          loading={datasourcesQuery.isLoading}
          data-testid="breakglass-datasource"
          options={[
            { value: 'all', label: t('breakglass.filter_datasource_all') },
            ...(datasourcesQuery.data?.content ?? []).map((d) => ({ value: d.id, label: d.name })),
          ]}
        />
        <Input
          placeholder={t('breakglass.filter_user_placeholder')}
          value={userId}
          onChange={(e) => {
            setUserId(e.target.value);
            setPage(0);
          }}
          style={{ width: 240 }}
          className="mono"
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
        <span className="mono muted u-text-2xs" style={{ alignSelf: 'center' }}>
          {t('breakglass.count_label', { count: eventsQuery.data?.total_elements ?? 0 })}
        </span>
      </FilterBar>
      <PageBody>
        {eventsQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} />
        ) : eventsQuery.isError ? (
          <EmptyState
            title={t('breakglass.load_error')}
            description={adminErrorMessage(eventsQuery.error)}
          />
        ) : events.length === 0 ? (
          <EmptyState
            icon={<ThunderboltOutlined className="u-text-xl" />}
            title={t('breakglass.empty_title')}
            description={t('breakglass.empty_description')}
          />
        ) : (
          <DataTable<BreakGlassEvent>
            rowKey="id"
            size="middle"
            dataSource={events}
            scroll={{ x: 'max-content' }}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: eventsQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            onRowActivate={(record) => setDetail(record)}
            columns={[
              {
                title: t('breakglass.col_executed'),
                dataIndex: 'created_at',
                width: 120,
                render: (v) => <span className="muted">{timeAgo(v)}</span>,
              },
              {
                title: t('breakglass.col_user'),
                width: 220,
                render: (_v, e) => {
                  const name =
                    userDisplay(e.submitted_by_display_name, e.submitted_by_email) ||
                    t('breakglass.user_unknown');
                  return (
                    <div className="u-flex u-items-center u-gap-2">
                      <Avatar name={name} size={20} />
                      <div>
                        <div className="u-text-xs">{name}</div>
                        {e.submitted_by_email && (
                          <div className="mono muted u-text-2xs">
                            {e.submitted_by_email}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                },
              },
              {
                title: t('breakglass.col_datasource'),
                dataIndex: 'datasource_name',
                width: 160,
                render: (v: string | null) =>
                  v ? (
                    <span className="mono u-text-xs">
                      {v}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  ),
              },
              {
                title: t('breakglass.col_justification'),
                dataIndex: 'justification',
                render: (v: string) => (
                  <span className="u-text-xs">
                    {v.length > 80 ? `${v.slice(0, 80)}…` : v}
                  </span>
                ),
              },
              {
                title: t('breakglass.col_status'),
                dataIndex: 'status',
                width: 140,
                render: (v: BreakGlassEventStatus) => <BreakGlassStatusPill status={v} size="sm" />,
              },
              {
                title: t('breakglass.col_actions'),
                width: 140,
                render: (_v, e) => {
                  // The submitter, and the human an agent broke glass for, can never sign off
                  // the retro-review (#1129); the server refuses it too.
                  const isOwnEvent =
                    currentUserId != null &&
                    (e.submitted_by_user_id === currentUserId ||
                      e.on_behalf_of_user_id === currentUserId);
                  return (
                    <Space size={4} onClick={(ev) => ev.stopPropagation()}>
                      <Tooltip
                        title={
                          isOwnEvent && e.status === 'PENDING_REVIEW'
                            ? t('breakglass.acknowledge_self_disabled')
                            : undefined
                        }
                      >
                        <Button
                          size="small"
                          disabled={e.status !== 'PENDING_REVIEW' || isOwnEvent}
                          onClick={() => {
                            setAckTarget(e);
                            setAckComment('');
                          }}
                          data-testid={`acknowledge-${e.id}`}
                        >
                          {t('breakglass.action_acknowledge')}
                        </Button>
                      </Tooltip>
                    </Space>
                  );
                },
              },
            ]}
          />
        )}
      </PageBody>

      <Modal
        open={!!ackTarget}
        title={t('breakglass.acknowledge_confirm_title')}
        okText={t('breakglass.action_acknowledge')}
        cancelText={t('common.cancel')}
        confirmLoading={acknowledge.isPending}
        onCancel={closeAck}
        onOk={() => {
          if (ackTarget) acknowledge.mutate({ id: ackTarget.id, comment: ackComment });
        }}
      >
        <p>{t('breakglass.acknowledge_confirm_body')}</p>
        <Input.TextArea
          value={ackComment}
          onChange={(e) => setAckComment(e.target.value)}
          placeholder={t('breakglass.acknowledge_comment_placeholder')}
          maxLength={4000}
          rows={3}
          data-testid="breakglass-ack-comment"
        />
      </Modal>

      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        title={t('breakglass.drawer_title')}
        size={560}
      >
        {detail && (
          <div className="u-flex u-col" style={{ gap: 14 }}>
            <DetailCard>
              <Space orientation="vertical" size={8} className="u-w-full">
                <BreakGlassStatusPill status={detail.status} size="sm" />
                {detail.query_request_id && (
                  <Link to={`/queries/${detail.query_request_id}`}>
                    {t('breakglass.view_query')}
                  </Link>
                )}
                {detail.api_request_id && (
                  <Link to={`/api-requests/${detail.api_request_id}`}>
                    {t('breakglass.view_api_request')}
                  </Link>
                )}
                {detail.deployment_request_id && (
                  <Link to={`/deployments/${detail.deployment_request_id}`}>
                    {t('breakglass.view_deployment')}
                  </Link>
                )}
              </Space>
            </DetailCard>
            <DetailCard title={t('breakglass.section_justification')}>
              <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.6 }}>{detail.justification}</div>
            </DetailCard>
            {detail.sql_text && (
              <DetailCard title={t('breakglass.section_query')}>
                <pre
                  className="u-m-0 u-mono u-text-xs u-leading-normal" style={{ padding: 14, background: 'var(--bg-code)', borderRadius: '0 0 var(--radius-md) var(--radius-md)', overflow: 'auto' }}
                >
                  {detail.sql_text}
                </pre>
              </DetailCard>
            )}
            <DetailCard title={t('breakglass.section_review')}>
              <div className="u-flex u-col" style={{ padding: 14, gap: 10 }}>
                <Row k={t('breakglass.field_executed')} v={fmtDate(detail.created_at)} />
                {detail.reviewed_at && (
                  <>
                    <Row
                      k={t('breakglass.field_reviewed_by')}
                      v={detail.reviewed_by_display_name ?? '—'}
                    />
                    <Row k={t('breakglass.field_reviewed_at')} v={fmtDate(detail.reviewed_at)} />
                    {detail.review_comment && (
                      <Row k={t('breakglass.field_review_comment')} v={detail.review_comment} />
                    )}
                  </>
                )}
              </div>
            </DetailCard>
          </div>
        )}
      </Drawer>
    </Page>
  );
}

function DetailCard({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      {title && (
        <div
          className="u-fw-600 u-text-xs" style={{ padding: '10px 14px', borderBottom: '1px solid var(--border)' }}
        >
          {title}
        </div>
      )}
      {!title ? <div style={{ padding: 14 }}>{children}</div> : children}
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
