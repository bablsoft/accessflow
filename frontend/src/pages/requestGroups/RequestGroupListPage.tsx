import { useMemo, useState } from 'react';
import { App, Button, Input, Select, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { RequestGroupStatusPill } from '@/components/common/RequestGroupStatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import {
  cancelRequestGroup,
  executeRequestGroup,
  listRequestGroups,
  requestGroupKeys,
} from '@/api/requestGroups';
import {
  enumOptions,
  REQUEST_GROUP_STATUSES,
  requestGroupStatusLabel,
} from '@/utils/enumLabels';
import { timeAgo } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { RequestGroup, RequestGroupStatus } from '@/types/api';

const PAGE_SIZE = 20;

export default function RequestGroupListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [q, setQ] = useState('');
  const [status, setStatus] = useState<RequestGroupStatus | 'all'>('all');
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      status: status === 'all' ? undefined : status,
      page,
      size: PAGE_SIZE,
    }),
    [status, page],
  );

  const groupsQuery = useQuery({
    queryKey: requestGroupKeys.list(filters),
    queryFn: () => listRequestGroups(filters),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: requestGroupKeys.lists() });

  const executeMutation = useMutation({
    mutationFn: executeRequestGroup,
    onSuccess: () => {
      message.success(t('requestGroups.list.executeStarted'));
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('requestGroups.error'))),
  });

  const cancelMutation = useMutation({
    mutationFn: cancelRequestGroup,
    onSuccess: () => {
      message.success(t('requestGroups.list.cancelled'));
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('requestGroups.error'))),
  });

  const rows = useMemo(() => groupsQuery.data?.content ?? [], [groupsQuery.data]);
  const filtered = useMemo(
    () =>
      rows.filter((r) => {
        if (!q) return true;
        const n = q.toLowerCase();
        return (
          r.name.toLowerCase().includes(n) ||
          r.id.toLowerCase().includes(n) ||
          (r.submitted_by_display_name ?? '').toLowerCase().includes(n)
        );
      }),
    [rows, q],
  );

  const columns: TableColumnsType<RequestGroup> = [
    {
      title: t('requestGroups.list.name'),
      dataIndex: 'name',
      render: (v: string) => <span style={{ fontWeight: 600 }}>{v}</span>,
    },
    {
      title: t('requestGroups.list.members'),
      width: 100,
      render: (_v, r) => <span className="mono">{r.items.length}</span>,
    },
    {
      title: t('requestGroups.list.status'),
      dataIndex: 'status',
      width: 150,
      render: (v: RequestGroupStatus) => <RequestGroupStatusPill status={v} />,
    },
    {
      title: t('requestGroups.list.risk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} />
        ) : (
          <span className="muted" style={{ fontSize: 11 }}>
            —
          </span>
        ),
    },
    {
      title: t('requestGroups.list.submitter'),
      width: 160,
      ellipsis: true,
      render: (_v, r) => (
        <span style={{ fontSize: 12 }}>
          {r.submitted_by_display_name ?? r.submitted_by_user_id.slice(0, 8)}
        </span>
      ),
    },
    {
      title: t('requestGroups.list.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => (
        <span className="muted" style={{ fontSize: 12 }}>
          {timeAgo(v)}
        </span>
      ),
    },
    {
      title: t('requestGroups.list.actions'),
      key: 'actions',
      width: 200,
      render: (_v, r) => (
        <span style={{ display: 'flex', gap: 8 }} onClick={(e) => e.stopPropagation()}>
          <Button size="small" onClick={() => navigate(`/request-groups/${r.id}`)}>
            {t('requestGroups.list.view')}
          </Button>
          {r.status === 'APPROVED' && (
            <Button
              size="small"
              type="primary"
              loading={executeMutation.isPending && executeMutation.variables === r.id}
              onClick={() => executeMutation.mutate(r.id)}
            >
              {t('requestGroups.list.execute')}
            </Button>
          )}
          {(r.status === 'PENDING_AI' ||
            r.status === 'PENDING_REVIEW' ||
            r.status === 'APPROVED') && (
            <Button
              size="small"
              danger
              loading={cancelMutation.isPending && cancelMutation.variables === r.id}
              onClick={() => cancelMutation.mutate(r.id)}
            >
              {t('requestGroups.list.cancel')}
            </Button>
          )}
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('requestGroups.list.title')}
        subtitle={t('requestGroups.list.subtitle')}
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/request-groups/new')}>
            {t('requestGroups.list.newGroup')}
          </Button>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          background: 'var(--bg-elev)',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder={t('requestGroups.list.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 240 }}
        />
        <Select
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('requestGroups.list.filterAllStatuses') },
            ...enumOptions(REQUEST_GROUP_STATUSES, requestGroupStatusLabel, t),
          ]}
          style={{ width: 180 }}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {groupsQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<RequestGroup>
            rowKey="id"
            dataSource={filtered}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('requestGroups.list.empty') }}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: groupsQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            onRow={(record) => ({
              onClick: () => navigate(`/request-groups/${record.id}`),
              style: { cursor: 'pointer' },
            })}
          />
        )}
      </div>
    </div>
  );
}
