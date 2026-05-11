import { useEffect, useMemo, useState } from 'react';
import {
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
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  createReviewPlan,
  deleteReviewPlan,
  listReviewPlans,
  reviewPlanKeys,
  updateReviewPlan,
} from '@/api/reviewPlans';
import { setupProgressKeys } from '@/api/admin';
import { reviewPlanErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { ReviewPlan, ReviewPlanWriteRequest } from '@/types/api';

interface ApproverFormRow {
  user_id: string | null;
  role: 'ADMIN' | 'REVIEWER' | null;
  stage: number;
}

interface PlanFormValues {
  name: string;
  description?: string;
  requires_ai_review: boolean;
  requires_human_approval: boolean;
  min_approvals_required: number;
  approval_timeout_hours: number;
  auto_approve_reads: boolean;
  approvers: ApproverFormRow[];
}

const DEFAULT_VALUES: PlanFormValues = {
  name: '',
  description: '',
  requires_ai_review: true,
  requires_human_approval: true,
  min_approvals_required: 1,
  approval_timeout_hours: 24,
  auto_approve_reads: false,
  approvers: [{ user_id: null, role: 'REVIEWER', stage: 1 }],
};

export function ReviewPlansPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ReviewPlan | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<PlanFormValues>();

  const plansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });

  const isOpen = creating || editing !== null;

  useEffect(() => {
    if (creating) {
      form.resetFields();
      form.setFieldsValue(DEFAULT_VALUES);
    } else if (editing) {
      form.resetFields();
      form.setFieldsValue({
        name: editing.name,
        description: editing.description ?? '',
        requires_ai_review: editing.requires_ai_review,
        requires_human_approval: editing.requires_human_approval,
        min_approvals_required: editing.min_approvals_required,
        approval_timeout_hours: editing.approval_timeout_hours,
        auto_approve_reads: editing.auto_approve_reads,
        approvers: editing.approvers.length
          ? editing.approvers.map((a) => ({ user_id: a.user_id, role: a.role, stage: a.stage }))
          : [{ user_id: null, role: 'REVIEWER', stage: 1 }],
      });
    }
  }, [creating, editing, form]);

  const closeModal = () => {
    setCreating(false);
    setEditing(null);
  };

  const createMutation = useMutation({
    mutationFn: (payload: ReviewPlanWriteRequest) => createReviewPlan(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: reviewPlanKeys.all });
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      message.success(t('admin.review_plans.create_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, reviewPlanErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; payload: ReviewPlanWriteRequest }) =>
      updateReviewPlan(vars.id, vars.payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: reviewPlanKeys.all });
      message.success(t('admin.review_plans.update_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, reviewPlanErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteReviewPlan(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: reviewPlanKeys.all });
      message.success(t('admin.review_plans.delete_success'));
    },
    onError: (err) => showApiError(message, err, reviewPlanErrorMessage),
  });

  const onFinish = (values: PlanFormValues) => {
    const payload: ReviewPlanWriteRequest = {
      name: values.name.trim(),
      description: values.description?.trim() || null,
      requires_ai_review: values.requires_ai_review,
      requires_human_approval: values.requires_human_approval,
      min_approvals_required: values.min_approvals_required,
      approval_timeout_hours: values.approval_timeout_hours,
      auto_approve_reads: values.auto_approve_reads,
      approvers: values.approvers.map((row) => ({
        user_id: row.user_id ?? null,
        role: row.role ?? null,
        stage: row.stage,
      })),
    };
    if (creating) {
      createMutation.mutate(payload);
    } else if (editing) {
      updateMutation.mutate({ id: editing.id, payload });
    }
  };

  const onDelete = (plan: ReviewPlan) =>
    modal.confirm({
      title: t('admin.review_plans.delete_confirm_title'),
      content: t('admin.review_plans.delete_confirm_body', { name: plan.name }),
      okType: 'danger',
      okText: t('common.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(plan.id),
    });

  const plans = useMemo(() => plansQuery.data ?? [], [plansQuery.data]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.review_plans.title')}
        subtitle={t('admin.review_plans.subtitle')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => plansQuery.refetch()}>
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
              {t('admin.review_plans.add_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {plansQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : plansQuery.isError ? (
          <EmptyState
            title={t('admin.review_plans.load_error')}
            description={reviewPlanErrorMessage(plansQuery.error)}
          />
        ) : plans.length === 0 ? (
          <EmptyState
            title={t('admin.review_plans.title')}
            description={t('admin.review_plans.empty')}
          />
        ) : (
          <Table<ReviewPlan>
            rowKey="id"
            size="middle"
            dataSource={plans}
            pagination={{ pageSize: 12 }}
            columns={[
              {
                title: t('admin.review_plans.col_name'),
                dataIndex: 'name',
                render: (v) => <span style={{ fontWeight: 500 }}>{v}</span>,
              },
              {
                title: t('admin.review_plans.col_description'),
                dataIndex: 'description',
                render: (v: string | null) =>
                  v ? <span className="muted">{v}</span> : <span className="muted">—</span>,
              },
              {
                title: t('admin.review_plans.col_ai'),
                dataIndex: 'requires_ai_review',
                width: 80,
                render: (v: boolean) => <BoolPill on={v} />,
              },
              {
                title: t('admin.review_plans.col_human'),
                dataIndex: 'requires_human_approval',
                width: 90,
                render: (v: boolean) => <BoolPill on={v} />,
              },
              {
                title: t('admin.review_plans.col_min_approvals'),
                dataIndex: 'min_approvals_required',
                width: 110,
                render: (v) => <span className="mono">{v}</span>,
              },
              {
                title: t('admin.review_plans.col_timeout_h'),
                dataIndex: 'approval_timeout_hours',
                width: 110,
                render: (v) => <span className="mono">{v}</span>,
              },
              {
                title: t('admin.review_plans.col_approvers'),
                dataIndex: 'approvers',
                width: 120,
                render: (v: ReviewPlan['approvers']) => (
                  <span className="mono">
                    {t('admin.review_plans.approvers_count', { count: v.length })}
                  </span>
                ),
              },
              {
                title: t('admin.review_plans.col_actions'),
                width: 110,
                render: (_v, plan) => (
                  <div style={{ display: 'flex', gap: 4 }}>
                    <Button
                      size="small"
                      type="text"
                      icon={<EditOutlined />}
                      aria-label={t('common.edit')}
                      onClick={() => {
                        setCreating(false);
                        setEditing(plan);
                      }}
                    />
                    <Button
                      size="small"
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={t('common.delete')}
                      onClick={() => onDelete(plan)}
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
            ? t('admin.review_plans.edit_modal_title', { name: editing.name })
            : t('admin.review_plans.create_modal_title')
        }
        onCancel={closeModal}
        onOk={() => form.submit()}
        okText={
          editing
            ? t('admin.review_plans.save_update')
            : t('admin.review_plans.save_create')
        }
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
        destroyOnHidden
        width={640}
      >
        <Form<PlanFormValues>
          form={form}
          layout="vertical"
          initialValues={DEFAULT_VALUES}
          onFinish={onFinish}
        >
          <Form.Item
            name="name"
            label={t('admin.review_plans.label_name')}
            rules={[{ required: true, max: 255, whitespace: true }]}
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('admin.review_plans.label_description')}
            rules={[{ max: 2000 }]}
          >
            <Input.TextArea rows={2} maxLength={2000} />
          </Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item
              name="requires_ai_review"
              label={t('admin.review_plans.label_requires_ai')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="requires_human_approval"
              label={t('admin.review_plans.label_requires_human')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="min_approvals_required"
              label={t('admin.review_plans.label_min_approvals')}
              rules={[{ required: true, type: 'number', min: 1, max: 10 }]}
            >
              <InputNumber min={1} max={10} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="approval_timeout_hours"
              label={t('admin.review_plans.label_timeout_h')}
              rules={[{ required: true, type: 'number', min: 1, max: 8760 }]}
            >
              <InputNumber min={1} max={8760} style={{ width: '100%' }} />
            </Form.Item>
          </div>

          <Form.Item
            name="auto_approve_reads"
            label={t('admin.review_plans.label_auto_approve_reads')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item label={t('admin.review_plans.label_approvers')} required>
            <Form.List
              name="approvers"
              rules={[
                {
                  validator: async (_rule, items: ApproverFormRow[]) => {
                    const requireHuman = !!form.getFieldValue('requires_human_approval');
                    if (requireHuman && (!items || items.length === 0)) {
                      throw new Error(t('admin.review_plans.validation_min_approvers'));
                    }
                  },
                },
              ]}
            >
              {(fields, { add, remove }, { errors }) => (
                <>
                  {fields.map(({ key, name }) => (
                    <div
                      key={key}
                      style={{
                        display: 'grid',
                        gridTemplateColumns: '1fr 1fr 90px 32px',
                        gap: 8,
                        marginBottom: 8,
                      }}
                    >
                      <Form.Item
                        name={[name, 'user_id']}
                        rules={[
                          {
                            validator: async (_rule, value) => {
                              const role = form.getFieldValue(['approvers', name, 'role']);
                              if (!value && !role) {
                                throw new Error(
                                  t('admin.review_plans.validation_user_or_role'),
                                );
                              }
                            },
                          },
                        ]}
                        style={{ marginBottom: 0 }}
                      >
                        <Input
                          placeholder={t('admin.review_plans.select_user_placeholder')}
                          allowClear
                        />
                      </Form.Item>
                      <Form.Item name={[name, 'role']} style={{ marginBottom: 0 }}>
                        <Select
                          allowClear
                          placeholder={t('admin.review_plans.select_role_placeholder')}
                          options={[
                            { value: 'REVIEWER', label: 'REVIEWER' },
                            { value: 'ADMIN', label: 'ADMIN' },
                          ]}
                        />
                      </Form.Item>
                      <Form.Item
                        name={[name, 'stage']}
                        rules={[{ required: true, type: 'number', min: 1 }]}
                        style={{ marginBottom: 0 }}
                      >
                        <InputNumber min={1} style={{ width: '100%' }} />
                      </Form.Item>
                      <Button
                        type="text"
                        icon={<DeleteOutlined />}
                        aria-label={t('admin.review_plans.approver_remove')}
                        onClick={() => remove(name)}
                      />
                    </div>
                  ))}
                  <Button
                    type="dashed"
                    block
                    icon={<PlusOutlined />}
                    onClick={() => add({ user_id: null, role: 'REVIEWER', stage: 1 })}
                  >
                    {t('admin.review_plans.approver_add')}
                  </Button>
                  <Form.ErrorList errors={errors} />
                </>
              )}
            </Form.List>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function BoolPill({ on }: { on: boolean }) {
  return on ? (
    <Pill
      fg="var(--risk-low)"
      bg="var(--risk-low-bg)"
      border="var(--risk-low-border)"
      withDot
      size="sm"
    >
      yes
    </Pill>
  ) : (
    <Pill
      fg="var(--fg-muted)"
      bg="var(--status-neutral-bg)"
      border="var(--status-neutral-border)"
      withDot
      size="sm"
    >
      no
    </Pill>
  );
}
