import { useEffect, useMemo, useState } from 'react';
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
  getRequestableDatasourceSchema,
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
  pre_approve_queries?: boolean;
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

  const selectedDatasource = Form.useWatch('datasource_id', form);
  const selectedSchemas = Form.useWatch('allowed_schemas', form);

  const schema = useQuery({
    queryKey: accessRequestKeys.schema(selectedDatasource ?? ''),
    queryFn: () => getRequestableDatasourceSchema(selectedDatasource as string),
    enabled: !!selectedDatasource,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const schemaOptions = useMemo(
    () => (schema.data?.schemas ?? []).map((s) => ({ value: s.name, label: s.name })),
    [schema.data],
  );

  const tableOptions = useMemo(() => {
    const schemas = schema.data?.schemas ?? [];
    const filter =
      selectedSchemas && selectedSchemas.length > 0 ? new Set(selectedSchemas) : null;
    const seen = new Set<string>();
    const opts: { value: string; label: string }[] = [];
    for (const s of schemas) {
      if (filter && !filter.has(s.name)) continue;
      for (const tb of s.tables) {
        if (seen.has(tb)) continue;
        seen.add(tb);
        opts.push({ value: tb, label: tb });
      }
    }
    return opts;
  }, [schema.data, selectedSchemas]);

  const mine = useQuery({
    queryKey: accessRequestKeys.mine({ size: 50 }),
    queryFn: () => listMyAccessRequests({ size: 50 }),
  });

  // The single most relevant request to surface above the form: the newest one
  // that is still pending approval or an approved grant that hasn't expired yet.
  const activeRequest = useMemo(() => {
    const items = mine.data?.content ?? [];
    return (
      [...items]
        .sort((a, b) => b.created_at.localeCompare(a.created_at))
        .find(
          (r) =>
            r.status === 'PENDING' ||
            (r.status === 'APPROVED' && (remainingTtlMs(r.expires_at) ?? 0) > 0),
        ) ?? null
    );
  }, [mine.data]);

  // Keep the highlighted TTL countdown fresh while an active grant is shown,
  // without re-fetching: a 1-minute tick re-renders the compact remaining time.
  const [, forceTick] = useState(0);
  const hasActiveTtl = activeRequest?.status === 'APPROVED';
  useEffect(() => {
    if (!hasActiveTtl) return;
    const id = setInterval(() => forceTick((n) => n + 1), 60_000);
    return () => clearInterval(id);
  }, [hasActiveTtl]);

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
        pre_approve_queries: values.pre_approve_queries === true,
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
      {r.pre_approve_queries && <Tag color="blue">{t('access.request.pre_approve_tag')}</Tag>}
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
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Button size="small" danger loading={cancel.isPending}>
              {t('access.request.cancel')}
            </Button>
          </Popconfirm>
        ) : null,
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('access.request.title')} subtitle={t('access.request.subtitle')} />

      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {activeRequest && (
          <Card
            title={t('access.request.active_title')}
            style={{ marginBottom: 24, maxWidth: 640, borderLeft: '3px solid var(--accent)' }}
          >
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                <span style={{ fontWeight: 600 }}>
                  {activeRequest.datasource_name ?? activeRequest.datasource_id}
                </span>
                <AccessStatusPill status={activeRequest.status} size="sm" />
              </div>
              {capabilityTags(activeRequest)}
              {activeRequest.status === 'APPROVED' ? (
                <div>{expiresCell(activeRequest)}</div>
              ) : (
                <span className="muted">{t('access.request.awaiting_approval')}</span>
              )}
            </Space>
          </Card>
        )}

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

        <h2 style={{ fontSize: 16, margin: '32px 0 12px' }}>{t('access.request.new_heading')}</h2>
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
                onChange={() =>
                  form.setFieldsValue({ allowed_schemas: undefined, allowed_tables: undefined })
                }
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
              <Select
                mode="tags"
                placeholder={t('access.request.schemas_placeholder')}
                tokenSeparators={[',']}
                options={schemaOptions}
                loading={schema.isLoading}
                optionFilterProp="label"
              />
            </Form.Item>

            <Form.Item name="allowed_tables" label={t('access.request.tables')}>
              <Select
                mode="tags"
                placeholder={t('access.request.tables_placeholder')}
                tokenSeparators={[',']}
                options={tableOptions}
                loading={schema.isLoading}
                optionFilterProp="label"
              />
            </Form.Item>

            <Form.Item
              name="pre_approve_queries"
              valuePropName="checked"
              extra={t('access.request.pre_approve_hint')}
              style={{ marginBottom: 12 }}
            >
              <Checkbox>{t('access.request.pre_approve_label')}</Checkbox>
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
      </div>
    </div>
  );
}
