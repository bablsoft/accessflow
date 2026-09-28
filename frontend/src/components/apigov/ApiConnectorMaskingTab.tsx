import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Switch,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  MinusCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import {
  apiConnectorMaskingPolicyKeys,
  createApiConnectorMaskingPolicy,
  deleteApiConnectorMaskingPolicy,
  listApiConnectorMaskingPolicies,
  updateApiConnectorMaskingPolicy,
} from '@/api/apiConnectorMaskingPolicies';
import { apiConnectorKeys, listApiOperations } from '@/api/apiConnectors';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import { listRoles, roleKeys } from '@/api/roles';
import {
  API_MASKING_MATCHER_TYPES,
  apiMaskingMatcherTypeLabel,
  enumOptions,
  MASKING_STRATEGIES,
  maskingStrategyLabel,
} from '@/utils/enumLabels';
import { roleSelectOptions } from '@/utils/roleOptions';
import { MaskingPreviewPanel } from '@/components/masking/MaskingPreviewPanel';
import { MaskingStrategyParamsFields } from '@/components/masking/MaskingStrategyParamsFields';
import {
  formValuesFromStrategyParams,
  strategyParamsFromForm,
  type StrategyParamFormValues,
} from '@/utils/maskingStrategyParams';
import { userDisplay } from '@/utils/userDisplay';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  ApiConnectorMaskingPolicy,
  ApiMaskingMatcherType,
  CreateApiConnectorMaskingPolicyInput,
  MaskingStrategy,
  User,
} from '@/types/api';
import { renderUserOption } from '@/components/common/renderUserOption';

export function ApiConnectorMaskingTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ApiConnectorMaskingPolicy | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const policiesQuery = useQuery({
    queryKey: apiConnectorMaskingPolicyKeys.list(connectorId),
    queryFn: () => listApiConnectorMaskingPolicies(connectorId),
  });
  const policies = policiesQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (policyId: string) => deleteApiConnectorMaskingPolicy(connectorId, policyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: apiConnectorMaskingPolicyKeys.list(connectorId),
      });
      message.success(t('apiGov.settings.masking.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.settings.masking.delete_error')));
    },
  });

  const onDelete = (policy: ApiConnectorMaskingPolicy) => {
    modal.confirm({
      title: t('apiGov.settings.masking.delete_confirm_title'),
      content: t('apiGov.settings.masking.delete_confirm_body'),
      okType: 'danger',
      okText: t('apiGov.settings.masking.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(policy.id),
    });
  };

  return (
    <div style={{ padding: 28 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 16,
          gap: 16,
        }}
      >
        <div>
          <div style={{ fontWeight: 600 }}>{t('apiGov.settings.masking.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('apiGov.settings.masking.description')}
          </div>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditing(null);
            setModalOpen(true);
          }}
        >
          {t('apiGov.settings.masking.add')}
        </Button>
      </div>

      {!policiesQuery.isLoading && policies.length === 0 ? (
        <EmptyState
          title={t('apiGov.settings.masking.empty_title')}
          description={t('apiGov.settings.masking.empty_description')}
        />
      ) : (
        <Table<ApiConnectorMaskingPolicy>
          rowKey="id"
          size="middle"
          loading={policiesQuery.isLoading}
          dataSource={policies}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('apiGov.settings.masking.col_matcher'),
              dataIndex: 'matcher_type',
              width: 150,
              render: (v: ApiMaskingMatcherType) => <Tag>{apiMaskingMatcherTypeLabel(t, v)}</Tag>,
            },
            {
              title: t('apiGov.settings.masking.col_field'),
              render: (_v, p) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  {p.operation_id ? `${p.operation_id} · ` : ''}
                  {p.field_ref}
                </span>
              ),
            },
            {
              title: t('apiGov.settings.masking.col_strategy'),
              dataIndex: 'strategy',
              width: 170,
              render: (v: MaskingStrategy) => <Tag>{maskingStrategyLabel(t, v)}</Tag>,
            },
            {
              title: t('apiGov.settings.masking.col_reveal'),
              render: (_v, p) => <RevealSummary policy={p} />,
            },
            {
              title: t('apiGov.settings.masking.col_enabled'),
              width: 90,
              align: 'center',
              render: (_v, p) => {
                const label = p.enabled
                  ? t('apiGov.settings.masking.label_enabled')
                  : t('apiGov.settings.masking.state_disabled');
                return (
                  <Tooltip title={label}>
                    {p.enabled ? (
                      <CheckCircleOutlined aria-label={label} style={{ color: 'var(--risk-low)' }} />
                    ) : (
                      <MinusCircleOutlined aria-label={label} className="muted" />
                    )}
                  </Tooltip>
                );
              },
            },
            {
              title: t('apiGov.settings.masking.col_actions'),
              width: 110,
              align: 'right',
              render: (_v, p) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('apiGov.settings.masking.edit')}
                    onClick={() => {
                      setEditing(p);
                      setModalOpen(true);
                    }}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('apiGov.settings.masking.delete')}
                    onClick={() => onDelete(p)}
                    disabled={deleteMutation.isPending}
                  />
                </>
              ),
            },
          ]}
        />
      )}

      <MaskingPolicyModal
        open={modalOpen}
        connectorId={connectorId}
        policy={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

function RevealSummary({ policy }: { policy: ApiConnectorMaskingPolicy }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (policy.reveal_to_roles.length > 0) {
    parts.push(t('apiGov.settings.masking.reveal_roles', { count: policy.reveal_to_roles.length }));
  }
  if (policy.reveal_to_group_ids.length > 0) {
    parts.push(t('apiGov.settings.masking.reveal_groups', { count: policy.reveal_to_group_ids.length }));
  }
  if (policy.reveal_to_user_ids.length > 0) {
    parts.push(t('apiGov.settings.masking.reveal_users', { count: policy.reveal_to_user_ids.length }));
  }
  if (parts.length === 0) {
    return (
      <span className="muted" style={{ fontSize: 12 }}>
        {t('apiGov.settings.masking.reveal_none')}
      </span>
    );
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

interface MaskingFormValues extends StrategyParamFormValues {
  matcher_type: ApiMaskingMatcherType;
  operation_id?: string;
  field_ref: string;
  strategy: MaskingStrategy;
  reveal_to_roles?: string[];
  reveal_to_group_ids?: string[];
  reveal_to_user_ids?: string[];
  enabled: boolean;
}

interface MaskingPolicyModalProps {
  open: boolean;
  connectorId: string;
  policy: ApiConnectorMaskingPolicy | null;
  onClose: () => void;
}

function MaskingPolicyModal({ open, connectorId, policy, onClose }: MaskingPolicyModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<MaskingFormValues>();
  const matcherType = Form.useWatch('matcher_type', form);
  const strategy = Form.useWatch('strategy', form);
  const previewParams = Form.useWatch(
    (values: MaskingFormValues) => strategyParamsFromForm(values.strategy, values),
    form,
  );
  const [sample, setSample] = useState('jane.doe@example.com');

  const operationsQuery = useQuery({
    queryKey: apiConnectorKeys.operations(connectorId),
    queryFn: () => listApiOperations(connectorId),
    enabled: open,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const usersQuery = useQuery({
    queryKey: userKeys.list({ size: 100 }),
    queryFn: () => listUsers({ size: 100 }),
    enabled: open,
  });
  const groupsQuery = useQuery({
    queryKey: groupKeys.lists(),
    queryFn: () => listAllGroups(),
    enabled: open,
  });
  const rolesQuery = useQuery({
    queryKey: roleKeys.lists(),
    queryFn: listRoles,
    enabled: open,
  });
  const roleOptions = useMemo(
    () => roleSelectOptions(rolesQuery.data ?? [], t, 'name'),
    [rolesQuery.data, t],
  );

  const operationOptions = useMemo(
    () =>
      (operationsQuery.data ?? []).map((op) => ({
        value: op.operation_id,
        label: `${op.verb} ${op.path}`,
      })),
    [operationsQuery.data],
  );
  const userOptions = useMemo(
    () =>
      (usersQuery.data?.content ?? [])
        .filter((u: User) => u.active)
        .map((u: User) => ({
          value: u.id,
          label: userDisplay(u.display_name, u.email),
          principal_type: u.principal_type ?? 'HUMAN',
        })),
    [usersQuery.data],
  );
  const groupOptions = useMemo(
    () => (groupsQuery.data ?? []).map((g) => ({ value: g.id, label: g.name })),
    [groupsQuery.data],
  );

  useEffect(() => {
    if (!open) return;
    if (policy) {
      form.setFieldsValue({
        matcher_type: policy.matcher_type,
        operation_id: policy.operation_id ?? undefined,
        field_ref: policy.field_ref,
        strategy: policy.strategy,
        ...formValuesFromStrategyParams(policy.strategy_params),
        reveal_to_roles: policy.reveal_to_roles,
        reveal_to_group_ids: policy.reveal_to_group_ids,
        reveal_to_user_ids: policy.reveal_to_user_ids,
        enabled: policy.enabled,
      });
    } else {
      form.resetFields();
    }
  }, [open, policy, form]);

  const saveMutation = useMutation({
    mutationFn: (input: CreateApiConnectorMaskingPolicyInput) =>
      policy
        ? updateApiConnectorMaskingPolicy(connectorId, policy.id, input)
        : createApiConnectorMaskingPolicy(connectorId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: apiConnectorMaskingPolicyKeys.list(connectorId),
      });
      message.success(t('apiGov.settings.masking.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.settings.masking.save_error')));
    },
  });

  const onFinish = (values: MaskingFormValues) => {
    const params = strategyParamsFromForm(values.strategy, values);
    saveMutation.mutate({
      matcher_type: values.matcher_type,
      operation_id: values.matcher_type === 'SCHEMA_FIELD' ? values.operation_id : undefined,
      field_ref: values.field_ref.trim(),
      strategy: values.strategy,
      strategy_params: params,
      reveal_to_roles: values.reveal_to_roles ?? [],
      reveal_to_group_ids: values.reveal_to_group_ids ?? [],
      reveal_to_user_ids: values.reveal_to_user_ids ?? [],
      enabled: values.enabled,
    });
  };

  return (
    <Modal
      open={open}
      title={
        policy
          ? t('apiGov.settings.masking.edit_title')
          : t('apiGov.settings.masking.create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={560}
    >
      <Form<MaskingFormValues>
        form={form}
        layout="vertical"
        initialValues={{ matcher_type: 'JSON_PATH', strategy: 'FULL', enabled: true }}
        onFinish={onFinish}
      >
        <Form.Item
          name="matcher_type"
          label={t('apiGov.settings.masking.label_matcher')}
          rules={[{ required: true, message: t('apiGov.settings.masking.matcher_required') }]}
        >
          <Select<ApiMaskingMatcherType>
            options={enumOptions(API_MASKING_MATCHER_TYPES, apiMaskingMatcherTypeLabel, t)}
          />
        </Form.Item>

        {matcherType === 'SCHEMA_FIELD' && (
          <Form.Item
            name="operation_id"
            label={t('apiGov.settings.masking.label_operation')}
            rules={[{ required: true, message: t('apiGov.settings.masking.operation_required') }]}
          >
            <Select
              showSearch={{ optionFilterProp: 'label' }}
              options={operationOptions}
              loading={operationsQuery.isLoading}
              placeholder={t('apiGov.settings.masking.operation_placeholder')}
            />
          </Form.Item>
        )}

        <Form.Item
          name="field_ref"
          label={t('apiGov.settings.masking.label_field')}
          extra={t(`apiGov.settings.masking.field_hint_${(matcherType ?? 'JSON_PATH').toLowerCase()}`)}
          rules={[
            { required: true, message: t('apiGov.settings.masking.field_required') },
            { max: 2048, message: t('apiGov.settings.masking.field_required') },
          ]}
        >
          <Input placeholder={t('apiGov.settings.masking.placeholder_field')} />
        </Form.Item>

        <Form.Item
          name="strategy"
          label={t('apiGov.settings.masking.label_strategy')}
          rules={[{ required: true, message: t('apiGov.settings.masking.strategy_required') }]}
        >
          <Select<MaskingStrategy> options={enumOptions(MASKING_STRATEGIES, maskingStrategyLabel, t)} />
        </Form.Item>

        <MaskingStrategyParamsFields strategy={strategy} />

        <Form.Item name="reveal_to_roles" label={t('apiGov.settings.masking.label_reveal_roles')}>
          <Select<string[]>
            mode="multiple"
            allowClear
            options={roleOptions}
            loading={rolesQuery.isLoading}
          />
        </Form.Item>

        <Form.Item name="reveal_to_group_ids" label={t('apiGov.settings.masking.label_reveal_groups')}>
          <Select<string[]>
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={groupOptions}
            loading={groupsQuery.isLoading}
          />
        </Form.Item>

        <Form.Item name="reveal_to_user_ids" label={t('apiGov.settings.masking.label_reveal_users')}>
          <Select<string[]>
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={userOptions}
            optionRender={renderUserOption}
            loading={usersQuery.isLoading}
          />
        </Form.Item>

        <Form.Item name="enabled" label={t('apiGov.settings.masking.label_enabled')} valuePropName="checked">
          <Switch />
        </Form.Item>

        <MaskingPreviewPanel
          strategy={strategy}
          params={previewParams}
          sample={sample}
          onSampleChange={setSample}
          label={t('apiGov.settings.masking.preview_label')}
          sampleAriaLabel={t('apiGov.settings.masking.preview_sample')}
        />
      </Form>
    </Modal>
  );
}
