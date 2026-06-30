import { useEffect, useMemo, useState } from 'react';
import { App, Button, Form, Input, Modal, Select, Skeleton, Switch, Table, Tag } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createPolicy,
  deletePolicy,
  lifecycleKeys,
  listPolicies,
  previewPolicy,
  type PolicyListFilters,
} from '@/api/lifecycle';
import { listDatasources } from '@/api/datasources';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  LIFECYCLE_ACTIONS,
  LIFECYCLE_TRANSFORMS,
  enumOptions,
  lifecycleActionLabel,
  lifecycleTransformLabel,
} from '@/utils/enumLabels';
import type {
  CreateRetentionPolicyRequest,
  LifecycleAction,
  LifecyclePreviewResponse,
  LifecycleTransform,
  RetentionPolicy,
} from '@/types/api';

const PAGE_SIZE = 20;

interface PolicyFormValues {
  datasource_id: string;
  name: string;
  description?: string;
  target_table?: string;
  target_columns?: string[];
  classification_tag?: string;
  timestamp_column: string;
  retention_window: string;
  action: LifecycleAction;
  transform_type?: LifecycleTransform;
  soft_delete_column?: string;
  enabled: boolean;
}

export default function LifecyclePoliciesListPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [preview, setPreview] = useState<LifecyclePreviewResponse | null>(null);

  const filters: PolicyListFilters = useMemo(() => ({ page, size: PAGE_SIZE }), [page]);

  const policiesQuery = useQuery({
    queryKey: lifecycleKeys.policyList(filters),
    queryFn: () => listPolicies(filters),
  });

  const createMutation = useMutation({
    mutationFn: (body: CreateRetentionPolicyRequest) => createPolicy(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: lifecycleKeys.policies() });
      message.success(t('lifecycle.policies.create_success'));
      setCreating(false);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deletePolicy(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: lifecycleKeys.policies() });
      message.success(t('lifecycle.policies.delete_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const previewMutation = useMutation({
    mutationFn: (id: string) => previewPolicy(id),
    onSuccess: (data) => setPreview(data),
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const confirmDelete = (policy: RetentionPolicy) => {
    modal.confirm({
      title: t('lifecycle.policies.delete_confirm_title'),
      content: t('lifecycle.policies.delete_confirm_body', { name: policy.name }),
      okText: t('common.delete'),
      okButtonProps: { danger: true },
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(policy.id),
    });
  };

  const policies = policiesQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('lifecycle.policies.title')}
        subtitle={t('lifecycle.policies.subtitle_count', {
          count: policiesQuery.data?.total_elements ?? 0,
        })}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => policiesQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('lifecycle.policies.create')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {policiesQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : policiesQuery.isError ? (
          <EmptyState
            title={t('lifecycle.policies.load_error')}
            description={adminErrorMessage(policiesQuery.error)}
          />
        ) : policies.length === 0 ? (
          <EmptyState
            title={t('lifecycle.policies.empty_title')}
            description={t('lifecycle.policies.empty_description')}
          />
        ) : (
          <Table<RetentionPolicy>
            rowKey="id"
            size="middle"
            dataSource={policies}
            scroll={{ x: 'max-content' }}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: policiesQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('lifecycle.policies.col_name'),
                dataIndex: 'name',
                render: (name: string, p) => (
                  <div>
                    <div style={{ fontSize: 13, fontWeight: 500 }}>{name}</div>
                    {p.datasource_name && (
                      <div className="mono muted" style={{ fontSize: 11 }}>
                        {p.datasource_name}
                      </div>
                    )}
                  </div>
                ),
              },
              {
                title: t('lifecycle.policies.col_target'),
                key: 'target',
                render: (_v, p) => (
                  <span className="mono" style={{ fontSize: 12 }}>
                    {p.target_table ?? p.classification_tag ?? '—'}
                  </span>
                ),
              },
              {
                title: t('lifecycle.policies.col_action'),
                dataIndex: 'action',
                width: 150,
                render: (action: LifecycleAction, p) => (
                  <span style={{ fontSize: 12 }}>
                    {lifecycleActionLabel(t, action)}
                    {p.transform_type && (
                      <span className="muted"> · {lifecycleTransformLabel(t, p.transform_type)}</span>
                    )}
                  </span>
                ),
              },
              {
                title: t('lifecycle.policies.col_window'),
                dataIndex: 'retention_window',
                width: 110,
                render: (v: string) => <span className="mono">{v}</span>,
              },
              {
                title: t('lifecycle.policies.col_enabled'),
                dataIndex: 'enabled',
                width: 100,
                render: (enabled: boolean) => (
                  <Tag color={enabled ? 'green' : 'default'}>
                    {enabled
                      ? t('lifecycle.policies.enabled')
                      : t('lifecycle.policies.disabled')}
                  </Tag>
                ),
              },
              {
                title: t('lifecycle.policies.col_actions'),
                key: 'actions',
                width: 170,
                align: 'right' as const,
                render: (_v, p) => (
                  <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                    <Button
                      size="small"
                      loading={previewMutation.isPending && previewMutation.variables === p.id}
                      onClick={() => previewMutation.mutate(p.id)}
                    >
                      {t('lifecycle.policies.preview')}
                    </Button>
                    <Button size="small" danger onClick={() => confirmDelete(p)}>
                      {t('common.delete')}
                    </Button>
                  </div>
                ),
              },
            ]}
          />
        )}
      </div>

      <CreatePolicyModal
        open={creating}
        onClose={() => setCreating(false)}
        onSubmit={(values) => createMutation.mutate(values)}
        loading={createMutation.isPending}
      />

      <Modal
        open={preview !== null}
        title={t('lifecycle.preview.title')}
        footer={null}
        onCancel={() => setPreview(null)}
        destroyOnHidden
      >
        {preview && (
          <>
            <p>
              {t('lifecycle.preview.total', {
                count: preview.total_estimated_rows,
              })}
            </p>
            <Table
              rowKey={(r) => r.table ?? r.method}
              size="small"
              pagination={false}
              dataSource={preview.tables}
              columns={[
                { title: t('lifecycle.preview.col_table'), dataIndex: 'table', render: (v) => v ?? '—' },
                { title: t('lifecycle.preview.col_method'), dataIndex: 'method' },
                {
                  title: t('lifecycle.preview.col_rows'),
                  dataIndex: 'estimated_rows',
                  align: 'right' as const,
                  render: (v: number) => (v < 0 ? t('lifecycle.preview.unknown') : v),
                },
              ]}
            />
          </>
        )}
      </Modal>
    </div>
  );
}

function CreatePolicyModal({
  open,
  onClose,
  onSubmit,
  loading,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (values: CreateRetentionPolicyRequest) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<PolicyFormValues>();
  const action = Form.useWatch('action', form);

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
      title={t('lifecycle.policies.create_modal_title')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('lifecycle.policies.create')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
    >
      <Form<PolicyFormValues>
        form={form}
        layout="vertical"
        initialValues={{ action: 'HARD_DELETE', enabled: true, timestamp_column: 'created_at' }}
        onFinish={(values) =>
          onSubmit({
            datasource_id: values.datasource_id,
            name: values.name.trim(),
            description: values.description?.trim() || null,
            target_table: values.target_table?.trim() || null,
            target_columns: values.target_columns ?? [],
            classification_tag: values.classification_tag?.trim() || null,
            timestamp_column: values.timestamp_column.trim(),
            retention_window: values.retention_window.trim(),
            action: values.action,
            transform_type: values.action === 'PSEUDONYMIZE' ? values.transform_type : null,
            soft_delete_column:
              values.action === 'SOFT_DELETE' ? values.soft_delete_column?.trim() || null : null,
            enabled: values.enabled,
          })
        }
      >
        <Form.Item
          name="datasource_id"
          label={t('lifecycle.policies.label_datasource')}
          rules={[{ required: true, message: t('validation.lifecycle_datasource_required') }]}
        >
          <Select
            placeholder={t('lifecycle.policies.datasource_placeholder')}
            loading={datasourcesQuery.isLoading}
            options={datasourceOptions}
            showSearch
            optionFilterProp="label"
          />
        </Form.Item>
        <Form.Item
          name="name"
          label={t('lifecycle.policies.label_name')}
          rules={[
            { required: true, message: t('validation.lifecycle_name_required') },
            { min: 3, max: 100, message: t('validation.lifecycle_name_size') },
          ]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item
          name="description"
          label={t('lifecycle.policies.label_description')}
          rules={[{ max: 2000, message: t('validation.lifecycle_description_max') }]}
        >
          <Input.TextArea rows={2} maxLength={2000} showCount />
        </Form.Item>
        <Form.Item
          name="target_table"
          label={t('lifecycle.policies.label_target_table')}
          extra={t('lifecycle.policies.target_help')}
          rules={[{ max: 255, message: t('validation.lifecycle_target_table_size') }]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="target_columns" label={t('lifecycle.policies.label_target_columns')}>
          <Select mode="tags" tokenSeparators={[',']} open={false} />
        </Form.Item>
        <Form.Item
          name="classification_tag"
          label={t('lifecycle.policies.label_classification_tag')}
          rules={[{ max: 100, message: t('validation.lifecycle_classification_tag_size') }]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item
          name="timestamp_column"
          label={t('lifecycle.policies.label_timestamp_column')}
          rules={[
            { required: true, message: t('validation.lifecycle_timestamp_column_required') },
            { max: 255, message: t('validation.lifecycle_timestamp_column_size') },
          ]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item
          name="retention_window"
          label={t('lifecycle.policies.label_retention_window')}
          extra={t('lifecycle.policies.retention_window_help')}
          rules={[
            { required: true, message: t('validation.lifecycle_retention_window_required') },
            { max: 50, message: t('validation.lifecycle_retention_window_size') },
          ]}
        >
          <Input placeholder="P30D" maxLength={50} />
        </Form.Item>
        <Form.Item
          name="action"
          label={t('lifecycle.policies.label_action')}
          rules={[{ required: true, message: t('validation.lifecycle_action_required') }]}
        >
          <Select options={enumOptions(LIFECYCLE_ACTIONS, lifecycleActionLabel, t)} />
        </Form.Item>
        {action === 'PSEUDONYMIZE' && (
          <Form.Item
            name="transform_type"
            label={t('lifecycle.policies.label_transform')}
            rules={[{ required: true, message: t('validation.lifecycle_transform_required') }]}
          >
            <Select options={enumOptions(LIFECYCLE_TRANSFORMS, lifecycleTransformLabel, t)} />
          </Form.Item>
        )}
        {action === 'SOFT_DELETE' && (
          <Form.Item
            name="soft_delete_column"
            label={t('lifecycle.policies.label_soft_delete_column')}
            extra={t('lifecycle.policies.soft_delete_column_help')}
            rules={[{ max: 255, message: t('validation.lifecycle_soft_delete_column_size') }]}
          >
            <Input placeholder="deleted_at" maxLength={255} />
          </Form.Item>
        )}
        <Form.Item name="enabled" label={t('lifecycle.policies.label_enabled')} valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
