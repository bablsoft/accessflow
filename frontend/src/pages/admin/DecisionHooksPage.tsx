import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Switch,
  Table,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  createDecisionHook,
  decisionHookKeys,
  deleteDecisionHook,
  listDecisionHooks,
  testDecisionHook,
  updateDecisionHook,
} from '@/api/decisionHooks';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import type { DecisionHook, DecisionHookTestResult, DecisionHookWriteRequest } from '@/types/api';
import { decisionHookErrorMessage } from '@/utils/apiErrors';
import { decisionHookFailureLabel, decisionHookOutcomeLabel } from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import {
  NAME_MAX,
  ORG_DEFAULT,
  SECRET_MAX,
  SECRET_MIN,
  TIMEOUT_MAX_MS,
  TIMEOUT_MIN_MS,
  URL_MAX,
  ENDPOINT_SCHEME,
  toFormValues,
  toWriteRequest,
  toggleEnabledRequest,
  type DecisionHookFormValues,
} from './decisionHookForm';

/**
 * `/admin/decision-hooks` (#945): the external policy decision hook consulted when no routing
 * policy matched — an organization default plus at most one hook per datasource. The signing
 * secret is write-only; the page only ever learns whether one is stored.
 */
export function DecisionHooksPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<DecisionHook | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<DecisionHookFormValues>();
  const includeSql = Form.useWatch('include_sql', form);

  const hooksQuery = useQuery({ queryKey: decisionHookKeys.lists(), queryFn: listDecisionHooks });
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ page: 0, size: 200 }),
    queryFn: () => listDatasources({ page: 0, size: 200 }),
    staleTime: 60_000,
  });
  const hooks = useMemo(() => hooksQuery.data ?? [], [hooksQuery.data]);
  const datasources = useMemo(
    () => datasourcesQuery.data?.content ?? [],
    [datasourcesQuery.data],
  );

  const isOpen = creating || editing !== null;

  useEffect(() => {
    if (!isOpen) return;
    form.resetFields();
    form.setFieldsValue(toFormValues(editing));
  }, [isOpen, editing, form]);

  const closeModal = () => {
    setCreating(false);
    setEditing(null);
  };

  const invalidate = () => queryClient.invalidateQueries({ queryKey: decisionHookKeys.all });

  const createMutation = useMutation({
    mutationFn: (payload: DecisionHookWriteRequest) => createDecisionHook(payload),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.decision_hooks.create_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, decisionHookErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: DecisionHookWriteRequest }) =>
      updateDecisionHook(id, payload),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.decision_hooks.update_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, decisionHookErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDecisionHook(id),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.decision_hooks.delete_success'));
    },
    onError: (err) => showApiError(message, err, decisionHookErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: (id: string) => testDecisionHook(id),
    onSuccess: (result: DecisionHookTestResult) => {
      const outcome = decisionHookOutcomeLabel(t, result.outcome);
      if (result.outcome === 'FAILED' && result.failure) {
        message.warning(
          t('admin.decision_hooks.test_failed', {
            failure: decisionHookFailureLabel(t, result.failure),
            latency: result.latency_ms,
          }),
        );
      } else {
        message.success(
          t('admin.decision_hooks.test_answered', { outcome, latency: result.latency_ms }),
        );
      }
    },
    onError: (err) => showApiError(message, err, decisionHookErrorMessage),
  });

  const onFinish = (values: DecisionHookFormValues) => {
    const payload = toWriteRequest(values);
    if (editing) {
      updateMutation.mutate({ id: editing.id, payload });
    } else {
      createMutation.mutate(payload);
    }
  };

  const onDelete = (hook: DecisionHook) =>
    modal.confirm({
      title: t('admin.decision_hooks.delete_confirm_title'),
      content: t('admin.decision_hooks.delete_confirm_body', { name: hook.name }),
      okType: 'danger',
      okText: t('common.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(hook.id),
    });

  const scopeLabel = (hook: DecisionHook) =>
    hook.datasource_id
      ? (datasources.find((d) => d.id === hook.datasource_id)?.name ?? hook.datasource_id)
      : t('admin.decision_hooks.scope_org_default');

  const scopeOptions = [
    { value: ORG_DEFAULT, label: t('admin.decision_hooks.scope_org_default') },
    ...datasources.map((d) => ({ value: d.id, label: d.name })),
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.decision_hooks.title')}
        subtitle={t('admin.decision_hooks.subtitle')}
        docsAnchor="cfg-decision-hooks"
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => hooksQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null);
                setCreating(true);
              }}
            >
              {t('admin.decision_hooks.add_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        <Alert
          type="info"
          showIcon
          style={{ margin: '12px 0' }}
          title={t('admin.decision_hooks.trust_title')}
          description={t('admin.decision_hooks.trust_body')}
        />
        {hooksQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 4 }} style={{ padding: 24 }} />
        ) : hooksQuery.isError ? (
          <EmptyState
            title={t('admin.decision_hooks.load_error')}
            description={decisionHookErrorMessage(hooksQuery.error)}
          />
        ) : hooks.length === 0 ? (
          <EmptyState
            title={t('admin.decision_hooks.title')}
            description={t('admin.decision_hooks.empty')}
          />
        ) : (
          <Table<DecisionHook>
            rowKey="id"
            size="middle"
            dataSource={hooks}
            scroll={{ x: 'max-content' }}
            pagination={false}
            columns={[
              {
                title: t('admin.decision_hooks.col_name'),
                dataIndex: 'name',
                render: (v: string, hook) => (
                  <div>
                    <div style={{ fontWeight: 500 }}>{v}</div>
                    <div className="muted mono" style={{ fontSize: 12 }}>
                      {hook.endpoint_url}
                    </div>
                  </div>
                ),
              },
              {
                title: t('admin.decision_hooks.col_scope'),
                width: 200,
                render: (_v, hook) => (
                  <Pill
                    fg="var(--fg)"
                    bg="var(--status-neutral-bg)"
                    border="var(--status-neutral-border)"
                    size="sm"
                  >
                    {scopeLabel(hook)}
                  </Pill>
                ),
              },
              {
                title: t('admin.decision_hooks.col_timeout'),
                dataIndex: 'timeout_ms',
                width: 110,
                render: (v: number) => t('admin.decision_hooks.timeout_value', { ms: v }),
              },
              {
                title: t('admin.decision_hooks.col_include_sql'),
                dataIndex: 'include_sql',
                width: 110,
                render: (v: boolean) => (v ? t('common.yes') : t('common.no')),
              },
              {
                title: t('admin.decision_hooks.col_enabled'),
                dataIndex: 'enabled',
                width: 90,
                render: (v: boolean, hook) => (
                  <Switch
                    size="small"
                    checked={v}
                    aria-label={t('admin.decision_hooks.col_enabled')}
                    disabled={updateMutation.isPending}
                    onChange={(checked) =>
                      updateMutation.mutate({
                        id: hook.id,
                        payload: toggleEnabledRequest(hook, checked),
                      })
                    }
                  />
                ),
              },
              {
                title: t('admin.decision_hooks.col_actions'),
                width: 140,
                render: (_v, hook) => (
                  <div style={{ display: 'flex', gap: 4 }}>
                    <Button
                      size="small"
                      type="text"
                      icon={<SendOutlined />}
                      aria-label={t('admin.decision_hooks.test_button')}
                      loading={testMutation.isPending && testMutation.variables === hook.id}
                      onClick={() => testMutation.mutate(hook.id)}
                    />
                    <Button
                      size="small"
                      type="text"
                      icon={<EditOutlined />}
                      aria-label={t('common.edit')}
                      onClick={() => {
                        setCreating(false);
                        setEditing(hook);
                      }}
                    />
                    <Button
                      size="small"
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={t('common.delete')}
                      onClick={() => onDelete(hook)}
                    />
                  </div>
                ),
              },
            ]}
          />
        )}
      </div>

      <Modal
        open={isOpen}
        title={
          editing
            ? t('admin.decision_hooks.edit_modal_title', { name: editing.name })
            : t('admin.decision_hooks.create_modal_title')
        }
        onCancel={closeModal}
        onOk={() => form.submit()}
        okText={editing ? t('admin.decision_hooks.save_update') : t('admin.decision_hooks.save_create')}
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
        destroyOnHidden
        width={640}
      >
        <Form<DecisionHookFormValues>
          form={form}
          name="decision-hook"
          layout="vertical"
          onFinish={onFinish}
        >
          {/* name ↔ @NotBlank @Size(max = 255) */}
          <Form.Item
            name="name"
            label={t('admin.decision_hooks.label_name')}
            rules={[{ required: true, whitespace: true, max: NAME_MAX }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="scope"
            label={t('admin.decision_hooks.label_scope')}
            extra={t('admin.decision_hooks.scope_help')}
          >
            <Select
              options={scopeOptions}
              showSearch={{ optionFilterProp: 'label' }}
              loading={datasourcesQuery.isLoading}
            />
          </Form.Item>
          {/* endpoint_url ↔ @NotBlank @Size(max = 2048); scheme + address are checked server-side */}
          <Form.Item
            name="endpoint_url"
            label={t('admin.decision_hooks.label_endpoint_url')}
            extra={t('admin.decision_hooks.endpoint_help')}
            // Only the scheme is checked here: an in-cluster short name such as http://opa:8181 is
            // valid, and the address rules depend on the deployment, so the server decides those.
            rules={[
              { required: true, whitespace: true, max: URL_MAX },
              { pattern: ENDPOINT_SCHEME, message: t('admin.decision_hooks.endpoint_scheme') },
            ]}
          >
            <Input placeholder="https://opa.example.com/v1/data/accessflow/decision" />
          </Form.Item>
          {/* timeout_ms ↔ @Min(100) @Max(10000) */}
          <Form.Item
            name="timeout_ms"
            label={t('admin.decision_hooks.label_timeout')}
            extra={t('admin.decision_hooks.timeout_help')}
            rules={[{ required: true, type: 'number', min: TIMEOUT_MIN_MS, max: TIMEOUT_MAX_MS }]}
          >
            <InputNumber min={TIMEOUT_MIN_MS} max={TIMEOUT_MAX_MS} step={100} style={{ width: 180 }} />
          </Form.Item>
          {/* secret ↔ @NotBlank on create, @Size(min = 32, max = 512) */}
          <Form.Item
            name="secret"
            label={t('admin.decision_hooks.label_secret')}
            extra={
              editing
                ? t('admin.decision_hooks.secret_keep_help')
                : t('admin.decision_hooks.secret_help')
            }
            rules={[{ required: !editing }, { min: SECRET_MIN, max: SECRET_MAX }]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder={editing ? '********' : ''}
            />
          </Form.Item>
          <div style={{ display: 'flex', gap: 32 }}>
            <Form.Item
              name="include_sql"
              label={t('admin.decision_hooks.label_include_sql')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="enabled"
              label={t('admin.decision_hooks.label_enabled')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </div>
          {includeSql && (
            <Alert
              type="warning"
              showIcon
              title={t('admin.decision_hooks.include_sql_warning')}
            />
          )}
        </Form>
      </Modal>
    </div>
  );
}

export default DecisionHooksPage;
