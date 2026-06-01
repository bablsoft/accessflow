import { App, Button, Card, Checkbox, Form, Input, Popconfirm, Select, Skeleton, Space, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { AccessStatusPill } from '@/components/common/AccessStatusPill';
import { formatDurationCompact, remainingTtlMs } from '@/utils/accessTtl';
import { showApiError } from '@/utils/showApiError';
import { reviewErrorMessage } from '@/utils/apiErrors';
import {
  accessRequestKeys,
  cancelAccessRequest,
  listMyAccessRequests,
  listRequestableDatasources,
  submitAccessRequest,
} from '@/api/accessRequests';
import type { AccessRequest } from '@/types/api';

const DURATIONS = ['PT1H', 'PT4H', 'PT8H', 'P1D', 'P3D', 'P7D'] as const;

interface RequestFormValues {
  datasource_id: string;
  capabilities: string[];
  allowed_schemas?: string[];
  allowed_tables?: string[];
  requested_duration: string;
  justification: string;
}

export function RequestAccessPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<RequestFormValues>();

  const datasources = useQuery({
    queryKey: accessRequestKeys.datasources(),
    queryFn: listRequestableDatasources,
  });

  const mine = useQuery({
    queryKey: accessRequestKeys.mine({ size: 50 }),
    queryFn: () => listMyAccessRequests({ size: 50 }),
  });

  const invalidateMine = () =>
    void queryClient.invalidateQueries({ queryKey: accessRequestKeys.all });

  const submit = useMutation({
    mutationFn: (values: RequestFormValues) =>
      submitAccessRequest({
        datasource_id: values.datasource_id,
        can_read: values.capabilities.includes('read'),
        can_write: values.capabilities.includes('write'),
        can_ddl: values.capabilities.includes('ddl'),
        allowed_schemas: values.allowed_schemas?.length ? values.allowed_schemas : null,
        allowed_tables: values.allowed_tables?.length ? values.allowed_tables : null,
        requested_duration: values.requested_duration,
        justification: values.justification,
      }),
    onSuccess: () => {
      invalidateMine();
      form.resetFields();
      message.success(t('access.request.submit_success'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const cancel = useMutation({
    mutationFn: (id: string) => cancelAccessRequest(id),
    onSuccess: () => {
      invalidateMine();
      message.success(t('access.request.cancel_success'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const capabilityTags = (r: AccessRequest) => (
    <Space size={4} wrap>
      {r.can_read && <Tag>{t('access.request.can_read')}</Tag>}
      {r.can_write && <Tag color="orange">{t('access.request.can_write')}</Tag>}
      {r.can_ddl && <Tag color="red">{t('access.request.can_ddl')}</Tag>}
    </Space>
  );

  const expiresCell = (r: AccessRequest) => {
    if (!r.expires_at) return <span className="muted">—</span>;
    const ms = remainingTtlMs(r.expires_at);
    if (ms === null) return <span className="muted">—</span>;
    if (r.status !== 'APPROVED' || ms <= 0) {
      return <span className="muted">{t('access.ttl.expired')}</span>;
    }
    return <Tag color="green">{t('access.ttl.remaining', { value: formatDurationCompact(ms) })}</Tag>;
  };

  const columns: TableColumnsType<AccessRequest> = [
    {
      title: t('access.request.datasource'),
      dataIndex: 'datasource_name',
      render: (_v, r) => r.datasource_name ?? r.datasource_id,
    },
    { title: t('access.request.capabilities'), render: (_v, r) => capabilityTags(r) },
    {
      title: t('access.request.duration'),
      dataIndex: 'requested_duration',
      render: (v: string) => <span className="mono">{v}</span>,
    },
    {
      title: t('access.columns.status'),
      dataIndex: 'status',
      render: (_v, r) => <AccessStatusPill status={r.status} size="sm" />,
    },
    { title: t('access.columns.expires'), render: (_v, r) => expiresCell(r) },
    {
      title: '',
      key: 'actions',
      align: 'right',
      render: (_v, r) =>
        r.status === 'PENDING' ? (
          <Popconfirm
            title={t('access.request.cancel_confirm')}
            onConfirm={() => cancel.mutate(r.id)}
            okText={t('access.request.cancel')}
          >
            <Button size="small" danger loading={cancel.isPending}>
              {t('access.request.cancel')}
            </Button>
          </Popconfirm>
        ) : null,
    },
  ];

  return (
    <div>
      <PageHeader title={t('access.request.title')} subtitle={t('access.request.subtitle')} />

      <Card style={{ marginBottom: 24, maxWidth: 640 }}>
        <Form<RequestFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => submit.mutate(values)}
          initialValues={{ capabilities: ['read'], requested_duration: 'PT4H' }}
        >
          <Form.Item
            name="datasource_id"
            label={t('access.request.datasource')}
            rules={[{ required: true, message: t('access.request.validation.datasource_required') }]}
          >
            <Select
              placeholder={t('access.request.datasource_placeholder')}
              loading={datasources.isLoading}
              options={(datasources.data ?? []).map((d) => ({ value: d.id, label: d.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>

          <Form.Item
            name="capabilities"
            label={t('access.request.capabilities')}
            rules={[{ required: true, message: t('access.request.validation.capability_required') }]}
          >
            <Checkbox.Group
              options={[
                { value: 'read', label: t('access.request.can_read') },
                { value: 'write', label: t('access.request.can_write') },
                { value: 'ddl', label: t('access.request.can_ddl') },
              ]}
            />
          </Form.Item>

          <Form.Item name="allowed_schemas" label={t('access.request.schemas')}>
            <Select mode="tags" placeholder={t('access.request.schemas_placeholder')} tokenSeparators={[',']} />
          </Form.Item>

          <Form.Item name="allowed_tables" label={t('access.request.tables')}>
            <Select mode="tags" placeholder={t('access.request.tables_placeholder')} tokenSeparators={[',']} />
          </Form.Item>

          <Form.Item
            name="requested_duration"
            label={t('access.request.duration')}
            rules={[{ required: true, message: t('access.request.validation.duration_required') }]}
          >
            <Select
              options={DURATIONS.map((d) => ({ value: d, label: t(`access.request.durations.${d}` as const) }))}
            />
          </Form.Item>

          <Form.Item
            name="justification"
            label={t('access.request.justification')}
            rules={[
              { required: true, message: t('access.request.validation.justification_required') },
              { max: 4000, message: t('access.request.validation.justification_max') },
            ]}
          >
            <Input.TextArea
              rows={3}
              maxLength={4000}
              placeholder={t('access.request.justification_placeholder')}
            />
          </Form.Item>

          <Button type="primary" htmlType="submit" loading={submit.isPending}>
            {t('access.request.submit')}
          </Button>
        </Form>
      </Card>

      <h2 style={{ fontSize: 16, margin: '0 0 12px' }}>{t('access.request.my_requests')}</h2>
      {mine.isLoading ? (
        <Skeleton active />
      ) : (mine.data?.content.length ?? 0) === 0 ? (
        <EmptyState title={t('access.request.empty')} />
      ) : (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={mine.data?.content ?? []}
          pagination={false}
          size="middle"
        />
      )}
    </div>
  );
}
