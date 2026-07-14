import { useEffect, useMemo, useState } from 'react';
import { App, Button, Card, Checkbox, Form, Input, Popconfirm, Segmented, Select, Skeleton, Space, Table, Tag } from 'antd';
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
  listRequestableConnectorOperations,
  listRequestableConnectors,
  listRequestableDatasources,
  submitAccessRequest,
} from '@/api/accessRequests';
import type { AccessRequest, AccessResourceKind } from '@/types/api';

const DURATIONS = ['PT1H', 'PT4H', 'PT8H', 'P1D', 'P3D', 'P7D'] as const;

interface RequestFormValues {
  resource_kind: AccessResourceKind;
  datasource_id?: string;
  connector_id?: string;
  capabilities: string[];
  allowed_schemas?: string[];
  allowed_tables?: string[];
  allowed_operations?: string[];
  requested_duration: string;
  justification: string;
  pre_approve_queries?: boolean;
}

export function RequestAccessPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<RequestFormValues>();

  const resourceKind = Form.useWatch('resource_kind', form) ?? 'DATASOURCE';
  const isConnector = resourceKind === 'API_CONNECTOR';

  const datasources = useQuery({
    queryKey: accessRequestKeys.datasources(),
    queryFn: listRequestableDatasources,
    enabled: !isConnector,
  });

  const connectors = useQuery({
    queryKey: accessRequestKeys.connectors(),
    queryFn: listRequestableConnectors,
    enabled: isConnector,
  });

  const selectedDatasource = Form.useWatch('datasource_id', form);
  const selectedConnector = Form.useWatch('connector_id', form);
  const selectedSchemas = Form.useWatch('allowed_schemas', form);

  const schema = useQuery({
    queryKey: accessRequestKeys.schema(selectedDatasource ?? ''),
    queryFn: () => getRequestableDatasourceSchema(selectedDatasource as string),
    enabled: !isConnector && !!selectedDatasource,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const operations = useQuery({
    queryKey: accessRequestKeys.connectorOperations(selectedConnector ?? ''),
    queryFn: () => listRequestableConnectorOperations(selectedConnector as string),
    enabled: isConnector && !!selectedConnector,
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

  const operationOptions = useMemo(
    () =>
      (operations.data ?? []).map((op) => ({
        value: op.operation_id,
        label: op.summary ? `${op.verb} ${op.path} — ${op.summary}` : `${op.verb} ${op.path}`,
      })),
    [operations.data],
  );

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
      submitAccessRequest(
        values.resource_kind === 'API_CONNECTOR'
          ? {
              connector_id: values.connector_id,
              can_read: values.capabilities.includes('read'),
              can_write: values.capabilities.includes('write'),
              can_ddl: false,
              allowed_operations: values.allowed_operations?.length
                ? values.allowed_operations
                : null,
              requested_duration: values.requested_duration,
              justification: values.justification,
            }
          : {
              datasource_id: values.datasource_id,
              can_read: values.capabilities.includes('read'),
              can_write: values.capabilities.includes('write'),
              can_ddl: values.capabilities.includes('ddl'),
              allowed_schemas: values.allowed_schemas?.length ? values.allowed_schemas : null,
              allowed_tables: values.allowed_tables?.length ? values.allowed_tables : null,
              requested_duration: values.requested_duration,
              justification: values.justification,
              pre_approve_queries: values.pre_approve_queries === true,
            },
      ),
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

  const kindTag = (r: AccessRequest) =>
    r.resource_kind === 'API_CONNECTOR' ? (
      <Tag color="geekblue">{t('access.kind.api_connector')}</Tag>
    ) : (
      <Tag>{t('access.kind.datasource')}</Tag>
    );

  const resourceName = (r: AccessRequest) =>
    r.connector_name ?? r.datasource_name ?? r.connector_id ?? r.datasource_id;

  const capabilityTags = (r: AccessRequest) => (
    <Space size={4} wrap>
      {r.can_read && <Tag>{t('access.request.can_read')}</Tag>}
      {r.can_write && <Tag color="orange">{t('access.request.can_write')}</Tag>}
      {r.can_ddl && <Tag color="red">{t('access.request.can_ddl')}</Tag>}
      {r.pre_approve_queries && <Tag color="blue">{t('access.request.pre_approve_tag')}</Tag>}
      {(r.allowed_operations?.length ?? 0) > 0 && (
        <Tag color="purple">
          {t('access.request.operations_tag', { count: r.allowed_operations?.length ?? 0 })}
        </Tag>
      )}
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
      title: t('access.request.resource'),
      render: (_v, r) => (
        <Space size={6} wrap>
          {kindTag(r)}
          <span>{resourceName(r)}</span>
        </Space>
      ),
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
                {kindTag(activeRequest)}
                <span style={{ fontWeight: 600 }}>{resourceName(activeRequest)}</span>
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
            initialValues={{
              resource_kind: 'DATASOURCE',
              capabilities: ['read'],
              requested_duration: 'PT4H',
            }}
          >
            <Form.Item name="resource_kind" label={t('access.request.resource_type')}>
              <Segmented
                options={[
                  { value: 'DATASOURCE', label: t('access.kind.datasource') },
                  { value: 'API_CONNECTOR', label: t('access.kind.api_connector') },
                ]}
                onChange={() =>
                  form.setFieldsValue({
                    datasource_id: undefined,
                    connector_id: undefined,
                    capabilities: ['read'],
                    allowed_schemas: undefined,
                    allowed_tables: undefined,
                    allowed_operations: undefined,
                    pre_approve_queries: undefined,
                  })
                }
              />
            </Form.Item>

            {isConnector ? (
              <Form.Item
                name="connector_id"
                label={t('access.request.connector')}
                rules={[{ required: true, message: t('access.request.validation.connector_required') }]}
              >
                <Select
                  placeholder={t('access.request.connector_placeholder')}
                  loading={connectors.isLoading}
                  options={(connectors.data ?? []).map((c) => ({
                    value: c.id,
                    label: `${c.name} (${c.protocol})`,
                  }))}
                  showSearch
                  optionFilterProp="label"
                  onChange={() => form.setFieldsValue({ allowed_operations: undefined })}
                />
              </Form.Item>
            ) : (
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
            )}

            <Form.Item
              name="capabilities"
              label={t('access.request.capabilities')}
              rules={[{ required: true, message: t('access.request.validation.capability_required') }]}
            >
              <Checkbox.Group
                options={
                  isConnector
                    ? [
                        { value: 'read', label: t('access.request.can_read') },
                        { value: 'write', label: t('access.request.can_write') },
                      ]
                    : [
                        { value: 'read', label: t('access.request.can_read') },
                        { value: 'write', label: t('access.request.can_write') },
                        { value: 'ddl', label: t('access.request.can_ddl') },
                      ]
                }
              />
            </Form.Item>

            {isConnector ? (
              <Form.Item
                name="allowed_operations"
                label={t('access.request.operations')}
                extra={t('access.request.operations_hint')}
              >
                <Select
                  mode="multiple"
                  placeholder={t('access.request.operations_placeholder')}
                  options={operationOptions}
                  loading={operations.isLoading}
                  optionFilterProp="label"
                />
              </Form.Item>
            ) : (
              <>
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
              </>
            )}

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
