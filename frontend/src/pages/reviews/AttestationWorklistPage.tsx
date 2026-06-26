import { useMemo, useState } from 'react';
import { App, Button, Skeleton, Space, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import { Avatar } from '@/components/common/Avatar';
import {
  AttestationBulkModal,
  type AttestationBulkDecision,
} from '@/components/attestation/AttestationBulkModal';
import { AttestationRevokeModal } from '@/components/attestation/AttestationRevokeModal';
import {
  attestationKeys,
  bulkDecideItems,
  certifyItem,
  listAttestationWorklist,
  revokeItem,
  type WorklistFilters,
} from '@/api/attestation';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { attestationItemDecisionLabel } from '@/utils/enumLabels';
import { attestationItemDecisionColor } from '@/utils/statusColors';
import type { AttestationBulkRowStatus, AttestationItem } from '@/types/api';

type BulkAction = AttestationBulkDecision | null;

export default function AttestationWorklistPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const [revokeTargetId, setRevokeTargetId] = useState<string | null>(null);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [bulkAction, setBulkAction] = useState<BulkAction>(null);
  const [rowStatuses, setRowStatuses] = useState<Record<string, AttestationBulkRowStatus>>({});

  const filters: WorklistFilters = { size: 50 };
  const { data, isLoading, refetch } = useQuery({
    queryKey: attestationKeys.worklistFor(filters),
    queryFn: () => listAttestationWorklist(filters),
  });

  const items = data?.content ?? [];

  const invalidateAfterDecision = () => {
    void queryClient.invalidateQueries({ queryKey: attestationKeys.worklist() });
    void queryClient.invalidateQueries({ queryKey: attestationKeys.campaigns() });
  };

  const certify = useMutation({
    mutationFn: (id: string) => certifyItem(id),
    onSuccess: () => {
      invalidateAfterDecision();
      message.success(t('attestation.worklist.on_certify'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const revoke = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment: string }) => revokeItem(id, comment),
    onSuccess: () => {
      invalidateAfterDecision();
      setRevokeTargetId(null);
      message.success(t('attestation.worklist.on_revoke'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const bulk = useMutation({
    mutationFn: ({ decision, comment }: { decision: AttestationBulkDecision; comment: string }) =>
      bulkDecideItems({ item_ids: selectedRowKeys, decision, comment: comment || undefined }),
    onSuccess: (response) => {
      const failures: Record<string, AttestationBulkRowStatus> = {};
      let success = 0;
      for (const row of response.results) {
        if (row.status === 'SUCCESS') {
          success += 1;
        } else {
          failures[row.item_id] = row.status;
        }
      }
      const failure = Object.keys(failures).length;
      // Keep failed rows selected so the user can retry; drop the successes.
      setSelectedRowKeys((prev) => prev.filter((id) => failures[id] !== undefined));
      setRowStatuses((prev) => ({ ...prev, ...failures }));
      invalidateAfterDecision();
      if (failure === 0) {
        message.success(
          t('attestation.worklist.bulk.toast_summary', { count: success, success, failure }),
        );
      } else {
        message.warning(t('attestation.worklist.bulk.toast_partial', { success, failure }));
      }
      setBulkAction(null);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const rowStatusTag = (status: AttestationBulkRowStatus) => {
    const labelKey =
      status === 'FORBIDDEN'
        ? 'attestation.worklist.bulk.row_status_forbidden'
        : status === 'INVALID_STATE'
          ? 'attestation.worklist.bulk.row_status_invalid_state'
          : 'attestation.worklist.bulk.row_status_not_found';
    const color = status === 'INVALID_STATE' ? 'warning' : 'error';
    return <Tag color={color}>{t(labelKey)}</Tag>;
  };

  const columns: TableColumnsType<AttestationItem> = useMemo(
    () => [
      {
        title: t('attestation.worklist.col_subject'),
        key: 'subject',
        render: (_: unknown, item: AttestationItem) => (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name={item.subject_user_email} size={24} />
            <div>
              <div style={{ fontSize: 13 }}>{item.subject_user_display_name}</div>
              <div className="mono muted" style={{ fontSize: 11 }}>
                {item.subject_user_email}
              </div>
            </div>
          </div>
        ),
      },
      {
        title: t('attestation.worklist.col_datasource'),
        key: 'datasource',
        render: (_: unknown, item: AttestationItem) => (
          <span className="mono" style={{ fontSize: 12 }}>
            {item.datasource_name ?? '—'}
          </span>
        ),
      },
      {
        title: t('attestation.worklist.col_capabilities'),
        key: 'capabilities',
        render: (_: unknown, item: AttestationItem) => <Capabilities item={item} />,
      },
      {
        title: t('attestation.worklist.col_decision'),
        key: 'decision',
        width: 160,
        render: (_: unknown, item: AttestationItem) => {
          const s = rowStatuses[item.id];
          if (s) return rowStatusTag(s);
          const c = attestationItemDecisionColor(item.decision);
          return (
            <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size="sm">
              {attestationItemDecisionLabel(t, item.decision)}
            </Pill>
          );
        },
      },
      {
        title: t('attestation.worklist.col_actions'),
        key: 'actions',
        width: 200,
        align: 'right' as const,
        render: (_: unknown, item: AttestationItem) => (
          <Space size="small">
            <Button
              size="small"
              danger
              icon={<CloseOutlined />}
              onClick={() => setRevokeTargetId(item.id)}
            >
              {t('attestation.worklist.revoke')}
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              onClick={() => certify.mutate(item.id)}
            >
              {t('attestation.worklist.certify')}
            </Button>
          </Space>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t, rowStatuses],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('attestation.worklist.title')}
        subtitle={t('attestation.worklist.subtitle')}
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => refetch()}>
            {t('common.refresh')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {selectedRowKeys.length > 0 && (
          <div
            role="toolbar"
            aria-label={t('attestation.worklist.bulk.action_bar_count', {
              count: selectedRowKeys.length,
            })}
            style={{
              position: 'sticky',
              top: 0,
              zIndex: 10,
              background: 'var(--bg-elev)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-md)',
              padding: '10px 14px',
              marginBottom: 12,
              display: 'flex',
              alignItems: 'center',
              gap: 12,
            }}
          >
            <strong>
              {t('attestation.worklist.bulk.action_bar_count', { count: selectedRowKeys.length })}
            </strong>
            <div style={{ flex: 1 }} />
            <Button size="small" danger icon={<CloseOutlined />} onClick={() => setBulkAction('REVOKED')}>
              {t('attestation.worklist.bulk.revoke_selected')}
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              onClick={() => setBulkAction('CERTIFIED')}
            >
              {t('attestation.worklist.bulk.certify_selected')}
            </Button>
            <Button size="small" type="link" onClick={() => setSelectedRowKeys([])}>
              {t('attestation.worklist.bulk.clear_selection')}
            </Button>
          </div>
        )}
        {isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : items.length === 0 ? (
          <EmptyState
            title={t('attestation.worklist.empty_title')}
            description={t('attestation.worklist.empty_description')}
          />
        ) : (
          <Table<AttestationItem>
            rowKey="id"
            dataSource={items}
            columns={columns}
            pagination={false}
            size="middle"
            scroll={{ x: 'max-content' }}
            rowSelection={{
              selectedRowKeys,
              onChange: (keys) => setSelectedRowKeys(keys as string[]),
            }}
          />
        )}
      </div>
      <AttestationRevokeModal
        open={revokeTargetId !== null}
        loading={revoke.isPending}
        onCancel={() => setRevokeTargetId(null)}
        onConfirm={(comment) => {
          if (!revokeTargetId || !comment) return;
          revoke.mutate({ id: revokeTargetId, comment });
        }}
      />
      <AttestationBulkModal
        open={bulkAction !== null}
        decision={bulkAction ?? 'CERTIFIED'}
        selectedCount={selectedRowKeys.length}
        loading={bulk.isPending}
        onCancel={() => setBulkAction(null)}
        onConfirm={(comment) => {
          if (!bulkAction) return;
          bulk.mutate({ decision: bulkAction, comment });
        }}
      />
    </div>
  );
}

function Capabilities({ item }: { item: AttestationItem }) {
  const { t } = useTranslation();
  const caps: string[] = [];
  if (item.can_read) caps.push(t('attestation.detail.cap_read'));
  if (item.can_write) caps.push(t('attestation.detail.cap_write'));
  if (item.can_ddl) caps.push(t('attestation.detail.cap_ddl'));
  if (item.can_break_glass) caps.push(t('attestation.detail.cap_break_glass'));
  return (
    <span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 6 }}>
      {caps.map((c) => (
        <Pill key={c} size="sm">
          {c}
        </Pill>
      ))}
    </span>
  );
}
