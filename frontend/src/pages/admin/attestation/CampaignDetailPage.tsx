import { useMemo, useState } from 'react';
import { App, Button, Skeleton, Space, Table } from 'antd';
import {
  ArrowLeftOutlined,
  CloseCircleOutlined,
  DownloadOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import { Avatar } from '@/components/common/Avatar';
import {
  attestationKeys,
  cancelCampaign,
  exportEvidenceCsv,
  getCampaign,
  listCampaignItems,
  openCampaign,
  type CampaignItemsFilters,
  type EvidenceExportResult,
} from '@/api/attestation';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import {
  attestationCampaignScopeLabel,
  attestationCampaignStatusLabel,
  attestationItemDecisionLabel,
} from '@/utils/enumLabels';
import {
  attestationCampaignStatusColor,
  attestationItemDecisionColor,
} from '@/utils/statusColors';
import type { AttestationItem, AttestationItemDecision } from '@/types/api';

const PAGE_SIZE = 20;

export default function CampaignDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams<{ id: string }>();
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);

  const campaignQuery = useQuery({
    queryKey: attestationKeys.campaignDetail(id),
    queryFn: () => getCampaign(id),
    enabled: !!id,
  });

  const itemFilters: CampaignItemsFilters = useMemo(
    () => ({ page, size: PAGE_SIZE }),
    [page],
  );
  const itemsQuery = useQuery({
    queryKey: attestationKeys.campaignItems(id, itemFilters),
    queryFn: () => listCampaignItems(id, itemFilters),
    enabled: !!id,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: attestationKeys.campaigns() });
    void queryClient.invalidateQueries({ queryKey: attestationKeys.campaignDetail(id) });
  };

  const openMutation = useMutation({
    mutationFn: () => openCampaign(id),
    onSuccess: () => {
      invalidate();
      message.success(t('attestation.detail.open_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelCampaign(id),
    onSuccess: () => {
      invalidate();
      message.success(t('attestation.detail.cancel_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const exportMutation = useMutation({
    mutationFn: () => exportEvidenceCsv(id),
    onSuccess: (result: EvidenceExportResult) => {
      triggerDownload(result);
      if (result.truncated) {
        message.warning(t('attestation.detail.export_truncated'));
      }
    },
    onError: () => message.error(t('attestation.detail.export_failed')),
  });

  const campaign = campaignQuery.data;
  const items = itemsQuery.data?.content ?? [];

  if (campaignQuery.isLoading) {
    return <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 24 }} />;
  }
  if (campaignQuery.isError || !campaign) {
    return (
      <EmptyState
        title={t('attestation.detail.load_error')}
        description={adminErrorMessage(campaignQuery.error)}
        action={
          <Button onClick={() => navigate('/admin/attestation')}>
            {t('attestation.detail.back')}
          </Button>
        }
      />
    );
  }

  const statusColor = attestationCampaignStatusColor(campaign.status);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        breadcrumbs={[
          <a
            key="back"
            onClick={(e) => {
              e.preventDefault();
              navigate('/admin/attestation');
            }}
            href="/admin/attestation"
          >
            <ArrowLeftOutlined /> {t('attestation.list.title')}
          </a>,
          campaign.name,
        ]}
        title={campaign.name}
        subtitle={t('attestation.detail.subtitle')}
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => campaignQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            {campaign.status === 'SCHEDULED' && (
              <>
                <Button
                  danger
                  icon={<CloseCircleOutlined />}
                  loading={cancelMutation.isPending}
                  onClick={() =>
                    modal.confirm({
                      title: t('attestation.detail.cancel_confirm_title'),
                      content: t('attestation.detail.cancel_confirm_body'),
                      okType: 'danger',
                      okText: t('attestation.detail.cancel'),
                      cancelText: t('common.cancel'),
                      onOk: () => cancelMutation.mutateAsync(),
                    })
                  }
                >
                  {t('attestation.detail.cancel')}
                </Button>
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  loading={openMutation.isPending}
                  onClick={() => openMutation.mutate()}
                >
                  {t('attestation.detail.open_now')}
                </Button>
              </>
            )}
            <Button
              icon={<DownloadOutlined />}
              loading={exportMutation.isPending}
              onClick={() => exportMutation.mutate()}
            >
              {t('attestation.detail.export_evidence')}
            </Button>
          </Space>
        }
      />
      <div
        style={{
          padding: '16px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 28,
          alignItems: 'center',
        }}
      >
        <Pill fg={statusColor.fg} bg={statusColor.bg} border={statusColor.border} withDot>
          {attestationCampaignStatusLabel(t, campaign.status)}
        </Pill>
        <Stat label={t('attestation.detail.section_scope')}>
          {attestationCampaignScopeLabel(t, campaign.scope)}
          {campaign.datasource_name ? ` · ${campaign.datasource_name}` : ''}
        </Stat>
        <Stat label={t('attestation.detail.section_due')}>{fmtDate(campaign.due_at)}</Stat>
        <Stat label={t('attestation.detail.section_total')}>{campaign.total_items}</Stat>
        <Stat label={t('attestation.detail.section_pending')}>{campaign.pending_items}</Stat>
        <Stat label={t('attestation.detail.section_certified')}>{campaign.certified_items}</Stat>
        <Stat label={t('attestation.detail.section_revoked')}>{campaign.revoked_items}</Stat>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        <div style={{ padding: '14px 16px 6px', fontWeight: 600, fontSize: 13 }}>
          {t('attestation.detail.items_title')}
        </div>
        {itemsQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : items.length === 0 ? (
          <EmptyState
            title={t('attestation.detail.items_title')}
            description={t('attestation.detail.items_empty')}
          />
        ) : (
          <Table<AttestationItem>
            rowKey="id"
            size="middle"
            dataSource={items}
            scroll={{ x: 'max-content' }}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: itemsQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('attestation.detail.col_subject'),
                key: 'subject',
                render: (_v, item) => (
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
                title: t('attestation.detail.col_capabilities'),
                key: 'capabilities',
                render: (_v, item) => <Capabilities item={item} />,
              },
              {
                title: t('attestation.detail.col_decision'),
                dataIndex: 'decision',
                width: 130,
                render: (decision: AttestationItemDecision) => {
                  const c = attestationItemDecisionColor(decision);
                  return (
                    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size="sm">
                      {attestationItemDecisionLabel(t, decision)}
                    </Pill>
                  );
                },
              },
              {
                title: t('attestation.detail.col_decided_at'),
                dataIndex: 'decided_at',
                width: 170,
                render: (v: string | null) =>
                  v ? <span className="muted">{fmtDate(v)}</span> : <span className="muted">—</span>,
              },
            ]}
          />
        )}
      </div>
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

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div
        className="muted"
        style={{ fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '0.04em' }}
      >
        {label}
      </div>
      <div style={{ fontSize: 14, fontWeight: 500 }}>{children}</div>
    </div>
  );
}

function triggerDownload({ blob, filename }: EvidenceExportResult): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
