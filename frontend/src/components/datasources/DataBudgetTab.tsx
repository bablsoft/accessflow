import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
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
import { BytesInput } from '@/components/common/BytesInput';
import { renderUserOption } from '@/components/common/renderUserOption';
import {
  createDataBudget,
  dataBudgetKeys,
  deleteDataBudget,
  listDataBudgets,
  updateDataBudget,
} from '@/api/dataBudgets';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import { listRoles, roleKeys } from '@/api/roles';
import { roleSelectOptions } from '@/utils/roleOptions';
import { userDisplay } from '@/utils/userDisplay';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { formatBytes } from '@/utils/queryPlan';
import {
  DATA_BUDGET_BREACH_ACTIONS,
  dataBudgetBreachActionLabel,
  enumOptions,
} from '@/utils/enumLabels';
import {
  DATA_BUDGET_DEFAULT_WINDOW_MINUTES,
  DATA_BUDGET_NAME_MAX,
  DATA_BUDGET_THRESHOLD_MAX,
  DATA_BUDGET_THRESHOLD_MIN,
  DATA_BUDGET_WINDOW_MAX_MINUTES,
  DATA_BUDGET_WINDOW_MIN_MINUTES,
  DATA_BUDGET_WINDOW_UNITS,
  formatWindow,
  joinWindow,
  splitWindow,
  type DataBudgetWindowUnit,
} from '@/utils/dataBudget';
import type { DataBudget, DataBudgetBreachAction, DataBudgetInput, User } from '@/types/api';

export function DataBudgetTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<DataBudget | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const budgetsQuery = useQuery({
    queryKey: dataBudgetKeys.list(dsId),
    queryFn: () => listDataBudgets(dsId),
  });
  const budgets = budgetsQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (budgetId: string) => deleteDataBudget(dsId, budgetId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dataBudgetKeys.all });
      message.success(t('dataBudgets.tab.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('dataBudgets.tab.delete_error')),
      );
    },
  });

  const onAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const onDelete = (budget: DataBudget) => {
    modal.confirm({
      title: t('dataBudgets.tab.delete_confirm_title'),
      content: t('dataBudgets.tab.delete_confirm_body'),
      okType: 'danger',
      okText: t('dataBudgets.tab.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(budget.id),
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
          <div style={{ fontWeight: 600 }}>{t('dataBudgets.tab.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('dataBudgets.tab.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          {t('dataBudgets.tab.add')}
        </Button>
      </div>

      {!budgetsQuery.isLoading && budgets.length === 0 ? (
        <EmptyState
          title={t('dataBudgets.tab.empty_title')}
          description={t('dataBudgets.tab.empty_description')}
        />
      ) : (
        <Table<DataBudget>
          rowKey="id"
          size="middle"
          loading={budgetsQuery.isLoading}
          dataSource={budgets}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            { title: t('dataBudgets.tab.col_name'), dataIndex: 'name' },
            {
              title: t('dataBudgets.tab.col_limits'),
              render: (_v, b) => <LimitsSummary budget={b} />,
            },
            {
              title: t('dataBudgets.tab.col_window'),
              width: 110,
              render: (_v, b) => formatWindow(t, b.window_minutes),
            },
            {
              title: t('dataBudgets.tab.col_action'),
              width: 180,
              render: (_v, b) => dataBudgetBreachActionLabel(t, b.breach_action),
            },
            {
              title: t('dataBudgets.tab.col_applies_to'),
              render: (_v, b) => <AppliesToSummary budget={b} />,
            },
            {
              title: t('dataBudgets.tab.col_enabled'),
              width: 90,
              align: 'center',
              render: (_v, b) => {
                const label = b.enabled
                  ? t('dataBudgets.tab.label_enabled')
                  : t('dataBudgets.tab.state_disabled');
                return (
                  <Tooltip title={label}>
                    {b.enabled ? (
                      <CheckCircleOutlined aria-label={label} style={{ color: 'var(--risk-low)' }} />
                    ) : (
                      <MinusCircleOutlined aria-label={label} className="muted" />
                    )}
                  </Tooltip>
                );
              },
            },
            {
              title: t('dataBudgets.tab.col_actions'),
              width: 120,
              align: 'right',
              render: (_v, b) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('dataBudgets.tab.edit')}
                    onClick={() => {
                      setEditing(b);
                      setModalOpen(true);
                    }}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('dataBudgets.tab.delete')}
                    onClick={() => onDelete(b)}
                    disabled={deleteMutation.isPending}
                  />
                </>
              ),
            },
          ]}
        />
      )}

      <DataBudgetModal
        open={modalOpen}
        dsId={dsId}
        budget={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

function LimitsSummary({ budget }: { budget: DataBudget }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (budget.max_rows != null) {
    parts.push(t('dataBudgets.tab.rows_value', { value: budget.max_rows.toLocaleString() }));
  }
  if (budget.max_bytes != null) {
    parts.push(formatBytes(budget.max_bytes) ?? '');
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

function AppliesToSummary({ budget }: { budget: DataBudget }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (budget.applies_to_roles.length > 0) {
    parts.push(t('dataBudgets.tab.applies_roles', { count: budget.applies_to_roles.length }));
  }
  if (budget.applies_to_group_ids.length > 0) {
    parts.push(t('dataBudgets.tab.applies_groups', { count: budget.applies_to_group_ids.length }));
  }
  if (budget.applies_to_user_ids.length > 0) {
    parts.push(t('dataBudgets.tab.applies_users', { count: budget.applies_to_user_ids.length }));
  }
  if (parts.length === 0) {
    return (
      <span className="muted" style={{ fontSize: 12 }}>
        {t('dataBudgets.tab.applies_everyone')}
      </span>
    );
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

interface DataBudgetFormValues {
  name: string;
  max_rows?: number | null;
  max_bytes?: number | null;
  window_amount: number;
  window_unit: DataBudgetWindowUnit;
  breach_action: DataBudgetBreachAction;
  warn_threshold_percent?: number | null;
  applies_to_roles?: string[];
  applies_to_group_ids?: string[];
  applies_to_user_ids?: string[];
  enabled: boolean;
}

interface DataBudgetModalProps {
  open: boolean;
  dsId: string;
  budget: DataBudget | null;
  onClose: () => void;
}

const DEFAULT_VALUES: Partial<DataBudgetFormValues> = {
  ...(() => {
    const { amount, unit } = splitWindow(DATA_BUDGET_DEFAULT_WINDOW_MINUTES);
    return { window_amount: amount, window_unit: unit };
  })(),
  breach_action: 'REQUIRE_REVIEW',
  warn_threshold_percent: 80,
  enabled: true,
};

function DataBudgetModal({ open, dsId, budget, onClose }: DataBudgetModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<DataBudgetFormValues>();

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
  const unitOptions = DATA_BUDGET_WINDOW_UNITS.map((unit) => ({
    value: unit,
    label: t(`dataBudgets.tab.unit_${unit}` as const),
  }));

  useEffect(() => {
    if (!open) return;
    if (budget) {
      const { amount, unit } = splitWindow(budget.window_minutes);
      form.setFieldsValue({
        name: budget.name,
        max_rows: budget.max_rows ?? null,
        max_bytes: budget.max_bytes ?? null,
        window_amount: amount,
        window_unit: unit,
        breach_action: budget.breach_action,
        warn_threshold_percent: budget.warn_threshold_percent ?? null,
        applies_to_roles: budget.applies_to_roles,
        applies_to_group_ids: budget.applies_to_group_ids,
        applies_to_user_ids: budget.applies_to_user_ids,
        enabled: budget.enabled,
      });
    } else {
      form.resetFields();
    }
  }, [open, budget, form]);

  const saveMutation = useMutation({
    mutationFn: (input: DataBudgetInput) =>
      budget ? updateDataBudget(dsId, budget.id, input) : createDataBudget(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dataBudgetKeys.all });
      message.success(t('dataBudgets.tab.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('dataBudgets.tab.save_error')));
    },
  });

  const onFinish = (values: DataBudgetFormValues) => {
    saveMutation.mutate({
      name: values.name.trim(),
      max_rows: values.max_rows ?? null,
      max_bytes: values.max_bytes ?? null,
      window_minutes: joinWindow(values.window_amount, values.window_unit),
      breach_action: values.breach_action,
      warn_threshold_percent: values.warn_threshold_percent ?? null,
      applies_to_roles: values.applies_to_roles ?? [],
      applies_to_group_ids: values.applies_to_group_ids ?? [],
      applies_to_user_ids: values.applies_to_user_ids ?? [],
      enabled: values.enabled,
    });
  };

  // Backend: @Min(60) @Max(44640) on window_minutes (#942).
  const windowRule = {
    validator: (_: unknown, amount: number | null | undefined) => {
      const unit: DataBudgetWindowUnit = form.getFieldValue('window_unit') ?? 'hours';
      if (amount === null || amount === undefined) {
        return Promise.reject(new Error(t('dataBudgets.tab.window_range')));
      }
      const minutes = joinWindow(amount, unit);
      return minutes >= DATA_BUDGET_WINDOW_MIN_MINUTES && minutes <= DATA_BUDGET_WINDOW_MAX_MINUTES
        ? Promise.resolve()
        : Promise.reject(new Error(t('dataBudgets.tab.window_range')));
    },
  };
  // Backend: at least one of max_rows / max_bytes (chk_data_budgets_has_limit, 422).
  const limitRequiredRule = ({ getFieldValue }: { getFieldValue: (name: string) => unknown }) => ({
    validator: () =>
      getFieldValue('max_rows') == null && getFieldValue('max_bytes') == null
        ? Promise.reject(new Error(t('dataBudgets.tab.limit_required')))
        : Promise.resolve(),
  });

  return (
    <Modal
      open={open}
      title={budget ? t('dataBudgets.tab.edit_title') : t('dataBudgets.tab.create_title')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={600}
    >
      <Form<DataBudgetFormValues>
        form={form}
        name="data-budget"
        layout="vertical"
        initialValues={DEFAULT_VALUES}
        onFinish={onFinish}
      >
        <Form.Item
          name="name"
          label={t('dataBudgets.tab.label_name')}
          rules={[
            { required: true, whitespace: true, message: t('dataBudgets.tab.name_required') },
            { max: DATA_BUDGET_NAME_MAX, message: t('dataBudgets.tab.name_max') },
          ]}
        >
          <Input />
        </Form.Item>

        <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
          {t('dataBudgets.tab.limits_hint')}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Form.Item
            name="max_rows"
            label={t('dataBudgets.tab.label_max_rows')}
            dependencies={['max_bytes']}
            rules={[
              { type: 'number', min: 1, message: t('dataBudgets.tab.rows_min') },
              limitRequiredRule,
            ]}
          >
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="max_bytes"
            label={t('dataBudgets.tab.label_max_bytes')}
            dependencies={['max_rows']}
            rules={[
              { type: 'number', min: 1, message: t('dataBudgets.tab.bytes_min') },
              limitRequiredRule,
            ]}
          >
            <BytesInput aria-label={t('dataBudgets.tab.label_max_bytes')} />
          </Form.Item>
        </div>

        <Form.Item label={t('dataBudgets.tab.label_window')} extra={t('dataBudgets.tab.window_hint')}>
          <Space.Compact style={{ width: 260 }}>
            <Form.Item
              name="window_amount"
              noStyle
              dependencies={['window_unit']}
              rules={[windowRule]}
            >
              <InputNumber
                min={1}
                precision={0}
                aria-label={t('dataBudgets.tab.label_window')}
                style={{ width: '100%' }}
              />
            </Form.Item>
            <Form.Item name="window_unit" noStyle>
              <Select
                options={unitOptions}
                aria-label={t('dataBudgets.tab.window_unit')}
                style={{ width: 110 }}
              />
            </Form.Item>
          </Space.Compact>
        </Form.Item>

        <Form.Item
          name="breach_action"
          label={t('dataBudgets.tab.label_breach_action')}
          extra={t('dataBudgets.tab.breach_action_hint')}
        >
          <Select options={enumOptions(DATA_BUDGET_BREACH_ACTIONS, dataBudgetBreachActionLabel, t)} />
        </Form.Item>

        <Form.Item
          name="warn_threshold_percent"
          label={t('dataBudgets.tab.label_warn_threshold')}
          extra={t('dataBudgets.tab.warn_threshold_hint')}
          rules={[
            {
              type: 'number',
              min: DATA_BUDGET_THRESHOLD_MIN,
              max: DATA_BUDGET_THRESHOLD_MAX,
              message: t('dataBudgets.tab.threshold_range'),
            },
          ]}
        >
          <InputNumber
            min={DATA_BUDGET_THRESHOLD_MIN}
            max={DATA_BUDGET_THRESHOLD_MAX}
            precision={0}
            suffix="%"
            style={{ width: 140 }}
          />
        </Form.Item>

        <Form.Item
          name="applies_to_roles"
          label={t('dataBudgets.tab.label_applies_roles')}
          extra={t('dataBudgets.tab.applies_hint')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            options={roleOptions}
            loading={rolesQuery.isLoading}
          />
        </Form.Item>

        <Form.Item name="applies_to_group_ids" label={t('dataBudgets.tab.label_applies_groups')}>
          <Select<string[]>
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={groupOptions}
            loading={groupsQuery.isLoading}
          />
        </Form.Item>

        <Form.Item name="applies_to_user_ids" label={t('dataBudgets.tab.label_applies_users')}>
          <Select<string[]>
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={userOptions}
            optionRender={renderUserOption}
            loading={usersQuery.isLoading}
          />
        </Form.Item>

        <Form.Item name="enabled" label={t('dataBudgets.tab.label_enabled')} valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
