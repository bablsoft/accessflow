import { useEffect, useMemo, useState } from 'react';
import {
  App,
  AutoComplete,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
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
  createMaskingPolicy,
  deleteMaskingPolicy,
  listMaskingPolicies,
  maskingPolicyKeys,
  updateMaskingPolicy,
} from '@/api/maskingPolicies';
import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import {
  enumOptions,
  MASKING_STRATEGIES,
  maskingStrategyLabel,
  roleLabel,
} from '@/utils/enumLabels';
import { maskingPreview } from '@/utils/maskingPreview';
import { flattenSchemaToColumns } from '@/utils/schemaColumns';
import { userDisplay } from '@/utils/userDisplay';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  CreateMaskingPolicyInput,
  MaskingPolicy,
  MaskingStrategy,
  Role,
  User,
} from '@/types/api';

const REVEAL_ROLE_VALUES: Role[] = ['ADMIN', 'REVIEWER', 'ANALYST', 'READONLY'];

export function MaskingTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<MaskingPolicy | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const policiesQuery = useQuery({
    queryKey: maskingPolicyKeys.list(dsId),
    queryFn: () => listMaskingPolicies(dsId),
  });
  const policies = policiesQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (policyId: string) => deleteMaskingPolicy(dsId, policyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: maskingPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.masking.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('datasources.settings.masking.delete_error')));
    },
  });

  const onAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const onEdit = (policy: MaskingPolicy) => {
    setEditing(policy);
    setModalOpen(true);
  };

  const onDelete = (policy: MaskingPolicy) => {
    modal.confirm({
      title: t('datasources.settings.masking.delete_confirm_title'),
      content: t('datasources.settings.masking.delete_confirm_body'),
      okType: 'danger',
      okText: t('datasources.settings.masking.delete'),
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
          <div style={{ fontWeight: 600 }}>{t('datasources.settings.masking.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('datasources.settings.masking.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          {t('datasources.settings.masking.add')}
        </Button>
      </div>

      {!policiesQuery.isLoading && policies.length === 0 ? (
        <EmptyState
          title={t('datasources.settings.masking.empty_title')}
          description={t('datasources.settings.masking.empty_description')}
        />
      ) : (
        <Table<MaskingPolicy>
          rowKey="id"
          size="middle"
          loading={policiesQuery.isLoading}
          dataSource={policies}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('datasources.settings.masking.col_column'),
              dataIndex: 'column_ref',
              render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
            },
            {
              title: t('datasources.settings.masking.col_strategy'),
              dataIndex: 'strategy',
              width: 180,
              render: (v: MaskingStrategy) => <Tag>{maskingStrategyLabel(t, v)}</Tag>,
            },
            {
              title: t('datasources.settings.masking.col_reveal'),
              render: (_v, p) => <RevealSummary policy={p} />,
            },
            {
              title: t('datasources.settings.masking.col_enabled'),
              width: 90,
              align: 'center',
              render: (_v, p) => {
                const label = p.enabled
                  ? t('datasources.settings.masking.label_enabled')
                  : t('datasources.settings.masking.state_disabled');
                return (
                  <Tooltip title={label}>
                    {p.enabled ? (
                      <CheckCircleOutlined
                        aria-label={label}
                        style={{ color: 'var(--risk-low)' }}
                      />
                    ) : (
                      <MinusCircleOutlined aria-label={label} className="muted" />
                    )}
                  </Tooltip>
                );
              },
            },
            {
              title: t('datasources.settings.masking.col_actions'),
              width: 120,
              align: 'right',
              render: (_v, p) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('datasources.settings.masking.edit')}
                    onClick={() => onEdit(p)}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('datasources.settings.masking.delete')}
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
        dsId={dsId}
        policy={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

function RevealSummary({ policy }: { policy: MaskingPolicy }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (policy.reveal_to_roles.length > 0) {
    parts.push(t('datasources.settings.masking.reveal_roles', { count: policy.reveal_to_roles.length }));
  }
  if (policy.reveal_to_group_ids.length > 0) {
    parts.push(t('datasources.settings.masking.reveal_groups', { count: policy.reveal_to_group_ids.length }));
  }
  if (policy.reveal_to_user_ids.length > 0) {
    parts.push(t('datasources.settings.masking.reveal_users', { count: policy.reveal_to_user_ids.length }));
  }
  if (parts.length === 0) {
    return <span className="muted" style={{ fontSize: 12 }}>{t('datasources.settings.masking.reveal_none')}</span>;
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

interface MaskingFormValues {
  column_ref: string;
  strategy: MaskingStrategy;
  visible_suffix?: number | null;
  reveal_to_roles?: string[];
  reveal_to_group_ids?: string[];
  reveal_to_user_ids?: string[];
  enabled: boolean;
}

interface MaskingPolicyModalProps {
  open: boolean;
  dsId: string;
  policy: MaskingPolicy | null;
  onClose: () => void;
}

function MaskingPolicyModal({ open, dsId, policy, onClose }: MaskingPolicyModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<MaskingFormValues>();
  const strategy = Form.useWatch('strategy', form);
  const visibleSuffix = Form.useWatch('visible_suffix', form);
  const [sample, setSample] = useState('jane.doe@example.com');

  const schemaQuery = useQuery({
    queryKey: datasourceKeys.schema(dsId),
    queryFn: () => getDatasourceSchema(dsId),
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

  const columnOptions = useMemo(
    () => flattenSchemaToColumns(schemaQuery.data?.schemas ?? []),
    [schemaQuery.data],
  );
  const userOptions = useMemo(
    () =>
      (usersQuery.data?.content ?? [])
        .filter((u: User) => u.active)
        .map((u: User) => ({ value: u.id, label: userDisplay(u.display_name, u.email) })),
    [usersQuery.data],
  );
  const groupOptions = useMemo(
    () => (groupsQuery.data ?? []).map((g) => ({ value: g.id, label: g.name })),
    [groupsQuery.data],
  );

  useEffect(() => {
    if (!open) return;
    if (policy) {
      const suffix = policy.strategy_params?.visible_suffix;
      form.setFieldsValue({
        column_ref: policy.column_ref,
        strategy: policy.strategy,
        visible_suffix: suffix != null && suffix !== '' ? Number(suffix) : undefined,
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
    mutationFn: (input: CreateMaskingPolicyInput) =>
      policy
        ? updateMaskingPolicy(dsId, policy.id, input)
        : createMaskingPolicy(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: maskingPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.masking.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('datasources.settings.masking.save_error')));
    },
  });

  const onFinish = (values: MaskingFormValues) => {
    const params =
      values.strategy === 'PARTIAL' && values.visible_suffix != null
        ? { visible_suffix: String(values.visible_suffix) }
        : undefined;
    saveMutation.mutate({
      column_ref: values.column_ref.trim(),
      strategy: values.strategy,
      strategy_params: params,
      reveal_to_roles: values.reveal_to_roles ?? [],
      reveal_to_group_ids: values.reveal_to_group_ids ?? [],
      reveal_to_user_ids: values.reveal_to_user_ids ?? [],
      enabled: values.enabled,
    });
  };

  const previewValue = maskingPreview(
    strategy ?? 'FULL',
    sample,
    visibleSuffix != null ? { visible_suffix: String(visibleSuffix) } : undefined,
  );

  return (
    <Modal
      open={open}
      title={
        policy
          ? t('datasources.settings.masking.edit_title')
          : t('datasources.settings.masking.create_title')
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
        initialValues={{ strategy: 'FULL', enabled: true }}
        onFinish={onFinish}
      >
        <Form.Item
          name="column_ref"
          label={t('datasources.settings.masking.label_column_ref')}
          rules={[
            { required: true, message: t('datasources.settings.masking.column_required') },
            { max: 512, message: t('datasources.settings.masking.column_max') },
          ]}
        >
          <AutoComplete
            options={columnOptions}
            filterOption={(input, option) =>
              (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
            }
            placeholder={t('datasources.settings.masking.placeholder_column_ref')}
          />
        </Form.Item>

        <Form.Item
          name="strategy"
          label={t('datasources.settings.masking.label_strategy')}
          rules={[
            { required: true, message: t('datasources.settings.masking.strategy_required') },
          ]}
        >
          <Select<MaskingStrategy>
            options={enumOptions(MASKING_STRATEGIES, maskingStrategyLabel, t)}
          />
        </Form.Item>

        {strategy === 'PARTIAL' && (
          <Form.Item
            name="visible_suffix"
            label={t('datasources.settings.masking.label_visible_suffix')}
            rules={[
              {
                type: 'number',
                min: 1,
                max: 256,
                message: t('datasources.settings.masking.visible_suffix_range'),
              },
            ]}
          >
            <InputNumber min={1} max={256} style={{ width: 160 }} />
          </Form.Item>
        )}

        <Form.Item
          name="reveal_to_roles"
          label={t('datasources.settings.masking.label_reveal_roles')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            options={enumOptions(REVEAL_ROLE_VALUES, roleLabel, t)}
          />
        </Form.Item>

        <Form.Item
          name="reveal_to_group_ids"
          label={t('datasources.settings.masking.label_reveal_groups')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            optionFilterProp="label"
            options={groupOptions}
            loading={groupsQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="reveal_to_user_ids"
          label={t('datasources.settings.masking.label_reveal_users')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            optionFilterProp="label"
            options={userOptions}
            loading={usersQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="enabled"
          label={t('datasources.settings.masking.label_enabled')}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>

        <div
          style={{
            padding: '10px 12px',
            background: 'var(--bg-sunken)',
            borderRadius: 6,
          }}
        >
          <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
            {t('datasources.settings.masking.preview_label')}
          </div>
          <Input
            size="small"
            value={sample}
            onChange={(e) => setSample(e.target.value)}
            aria-label={t('datasources.settings.masking.preview_sample')}
            style={{ marginBottom: 8 }}
          />
          <Typography.Text className="mono" copyable={previewValue !== ''}>
            {previewValue === '' ? '—' : previewValue}
          </Typography.Text>
        </div>
      </Form>
    </Modal>
  );
}
