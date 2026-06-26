import { useEffect, useMemo, useState } from 'react';
import { App, Button, DatePicker, Form, Input, Modal, Select, Skeleton, Table } from 'antd';
import type { Dayjs } from 'dayjs';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  attestationKeys,
  createCampaign,
  listCampaigns,
  type CampaignListFilters,
} from '@/api/attestation';
import { listDatasources } from '@/api/datasources';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import {
  ATTESTATION_CAMPAIGN_SCOPES,
  ATTESTATION_CAMPAIGN_STATUSES,
  ATTESTATION_PENDING_DEFAULTS,
  attestationCampaignScopeLabel,
  attestationCampaignStatusLabel,
  attestationPendingDefaultLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { attestationCampaignStatusColor } from '@/utils/statusColors';
import type {
  AttestationCampaign,
  AttestationCampaignScope,
  AttestationCampaignStatus,
  AttestationPendingDefault,
  CreateAttestationCampaignRequest,
} from '@/types/api';

const PAGE_SIZE = 20;

interface CampaignFormValues {
  name: string;
  description?: string;
  scope: AttestationCampaignScope;
  datasource_id?: string;
  pending_default: AttestationPendingDefault;
  scheduled_open_at: Dayjs;
  due_at: Dayjs;
}

export default function CampaignListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<'all' | AttestationCampaignStatus>('all');
  const [creating, setCreating] = useState(false);

  const filters: CampaignListFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      status: statusFilter === 'all' ? undefined : statusFilter,
    }),
    [page, statusFilter],
  );

  const campaignsQuery = useQuery({
    queryKey: attestationKeys.campaignList(filters),
    queryFn: () => listCampaigns(filters),
  });

  const createMutation = useMutation({
    mutationFn: (body: CreateAttestationCampaignRequest) => createCampaign(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: attestationKeys.campaigns() });
      message.success(t('attestation.create.success'));
      setCreating(false);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const campaigns = campaignsQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('attestation.list.title')}
        subtitle={t('attestation.list.subtitle_count', {
          count: campaignsQuery.data?.total_elements ?? 0,
        })}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => campaignsQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('attestation.list.create')}
            </Button>
          </>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          gap: 8,
        }}
      >
        <Select
          value={statusFilter}
          onChange={(v) => {
            setStatusFilter(v);
            setPage(0);
          }}
          style={{ width: 180 }}
          options={[
            { value: 'all', label: t('attestation.list.filter_all_statuses') },
            ...enumOptions(ATTESTATION_CAMPAIGN_STATUSES, attestationCampaignStatusLabel, t),
          ]}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {campaignsQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : campaignsQuery.isError ? (
          <EmptyState
            title={t('attestation.list.load_error')}
            description={adminErrorMessage(campaignsQuery.error)}
          />
        ) : campaigns.length === 0 ? (
          <EmptyState
            title={t('attestation.list.empty_title')}
            description={t('attestation.list.empty_description')}
          />
        ) : (
          <Table<AttestationCampaign>
            rowKey="id"
            size="middle"
            dataSource={campaigns}
            scroll={{ x: 'max-content' }}
            onRow={(record) => ({
              onClick: () => navigate(`/admin/attestation/${record.id}`),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: campaignsQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('attestation.list.col_name'),
                dataIndex: 'name',
                render: (name: string, c) => (
                  <div>
                    <div style={{ fontSize: 13, fontWeight: 500 }}>{name}</div>
                    {c.datasource_name && (
                      <div className="mono muted" style={{ fontSize: 11 }}>
                        {c.datasource_name}
                      </div>
                    )}
                  </div>
                ),
              },
              {
                title: t('attestation.list.col_scope'),
                dataIndex: 'scope',
                width: 130,
                render: (scope: AttestationCampaignScope) => (
                  <span style={{ fontSize: 12 }}>{attestationCampaignScopeLabel(t, scope)}</span>
                ),
              },
              {
                title: t('attestation.list.col_status'),
                dataIndex: 'status',
                width: 130,
                render: (status: AttestationCampaignStatus) => {
                  const c = attestationCampaignStatusColor(status);
                  return (
                    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size="sm">
                      {attestationCampaignStatusLabel(t, status)}
                    </Pill>
                  );
                },
              },
              {
                title: t('attestation.list.col_due'),
                dataIndex: 'due_at',
                width: 170,
                render: (v: string) => <span className="muted">{fmtDate(v)}</span>,
              },
              {
                title: t('attestation.list.col_items'),
                dataIndex: 'total_items',
                width: 90,
                align: 'right' as const,
                render: (v: number) => <span className="mono">{v}</span>,
              },
              {
                title: t('attestation.list.col_actions'),
                key: 'actions',
                width: 90,
                align: 'right' as const,
                render: (_v, c) => (
                  <Button
                    size="small"
                    onClick={(e) => {
                      e.stopPropagation();
                      navigate(`/admin/attestation/${c.id}`);
                    }}
                  >
                    {t('attestation.list.view')}
                  </Button>
                ),
              },
            ]}
          />
        )}
      </div>

      <CreateCampaignModal
        open={creating}
        onClose={() => setCreating(false)}
        onSubmit={(values) => createMutation.mutate(values)}
        loading={createMutation.isPending}
      />
    </div>
  );
}

function CreateCampaignModal({
  open,
  onClose,
  onSubmit,
  loading,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (values: CreateAttestationCampaignRequest) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<CampaignFormValues>();
  const scope = Form.useWatch('scope', form);

  const datasourcesQuery = useQuery({
    queryKey: ['datasources', 'list', { page: 0, size: 100 }],
    queryFn: () => listDatasources({ page: 0, size: 100 }),
    enabled: open,
  });

  useEffect(() => {
    if (open) {
      form.resetFields();
    }
  }, [open, form]);

  const datasourceOptions = (datasourcesQuery.data?.content ?? []).map((d) => ({
    value: d.id,
    label: d.name,
  }));

  return (
    <Modal
      open={open}
      title={t('attestation.create.modal_title')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('attestation.create.submit')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
    >
      <Form<CampaignFormValues>
        form={form}
        layout="vertical"
        initialValues={{ scope: 'ORGANIZATION', pending_default: 'KEEP' }}
        onFinish={(values) =>
          onSubmit({
            name: values.name.trim(),
            description: values.description?.trim() || null,
            scope: values.scope,
            datasource_id: values.scope === 'DATASOURCE' ? values.datasource_id : null,
            pending_default: values.pending_default,
            scheduled_open_at: values.scheduled_open_at.toISOString(),
            due_at: values.due_at.toISOString(),
          })
        }
      >
        <Form.Item
          name="name"
          label={t('attestation.create.label_name')}
          rules={[
            { required: true, message: t('validation.attestation_name_required') },
            { min: 3, max: 100, message: t('validation.attestation_name_size') },
          ]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item
          name="description"
          label={t('attestation.create.label_description')}
          rules={[{ max: 2000, message: t('validation.attestation_description_max') }]}
        >
          <Input.TextArea rows={3} maxLength={2000} showCount />
        </Form.Item>
        <Form.Item
          name="scope"
          label={t('attestation.create.label_scope')}
          rules={[{ required: true, message: t('validation.attestation_scope_required') }]}
        >
          <Select options={enumOptions(ATTESTATION_CAMPAIGN_SCOPES, attestationCampaignScopeLabel, t)} />
        </Form.Item>
        {scope === 'DATASOURCE' && (
          <Form.Item
            name="datasource_id"
            label={t('attestation.create.label_datasource')}
            rules={[{ required: true, message: t('validation.attestation_datasource_required') }]}
          >
            <Select
              placeholder={t('attestation.create.datasource_placeholder')}
              loading={datasourcesQuery.isLoading}
              options={datasourceOptions}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
        )}
        <Form.Item
          name="pending_default"
          label={t('attestation.create.label_pending_default')}
          extra={t('attestation.create.pending_default_help')}
          rules={[{ required: true }]}
        >
          <Select
            options={enumOptions(ATTESTATION_PENDING_DEFAULTS, attestationPendingDefaultLabel, t)}
          />
        </Form.Item>
        <Form.Item
          name="scheduled_open_at"
          label={t('attestation.create.label_scheduled_open_at')}
          rules={[{ required: true, message: t('validation.attestation_scheduled_open_required') }]}
        >
          <DatePicker showTime style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="due_at"
          label={t('attestation.create.label_due_at')}
          dependencies={['scheduled_open_at']}
          rules={[
            { required: true, message: t('validation.attestation_due_required') },
            ({ getFieldValue }) => ({
              validator(_, value: Dayjs | undefined) {
                const open = getFieldValue('scheduled_open_at') as Dayjs | undefined;
                if (!value || !open || value.isAfter(open)) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error(t('validation.attestation_due_after_open')));
              },
            }),
          ]}
        >
          <DatePicker showTime style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
