import { useEffect, useState } from 'react';
import { App, Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Switch, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  createDeploymentEnvironment,
  deleteDeploymentEnvironment,
  deploymentPipelineKeys,
  listDeploymentEnvironments,
  updateDeploymentEnvironment,
} from '@/api/deploymentPipelines';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { DeploymentEnvironment } from '@/types/api';

interface EnvironmentFormValues {
  name: string;
  sort_order: number;
  require_review: boolean;
  required_approvals?: number | null;
  review_plan_id?: string | null;
  allow_break_glass: boolean;
}

export function PipelineEnvironmentsTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [modalFor, setModalFor] = useState<'create' | DeploymentEnvironment | null>(null);
  const [form] = Form.useForm<EnvironmentFormValues>();

  const envsQuery = useQuery({
    queryKey: deploymentPipelineKeys.environments(pipelineId),
    queryFn: () => listDeploymentEnvironments(pipelineId),
  });
  // Always loaded: the table's review-plan column resolves names from it too.
  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: () => listReviewPlans(),
  });

  useEffect(() => {
    if (modalFor === null) return;
    if (modalFor === 'create') {
      form.setFieldsValue({
        name: '',
        sort_order: (envsQuery.data?.length ?? 0) * 10,
        require_review: true,
        required_approvals: null,
        review_plan_id: null,
        allow_break_glass: false,
      });
    } else {
      form.setFieldsValue({
        name: modalFor.name,
        sort_order: modalFor.sort_order,
        require_review: modalFor.require_review,
        required_approvals: modalFor.required_approvals,
        review_plan_id: modalFor.review_plan_id,
        allow_break_glass: modalFor.allow_break_glass,
      });
    }
  }, [modalFor, form, envsQuery.data]);

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: deploymentPipelineKeys.environments(pipelineId) });

  const saveMutation = useMutation({
    mutationFn: (values: EnvironmentFormValues) => {
      if (modalFor === 'create') {
        return createDeploymentEnvironment(pipelineId, {
          name: values.name,
          sort_order: values.sort_order,
          require_review: values.require_review,
          required_approvals: values.required_approvals ?? null,
          review_plan_id: values.review_plan_id ?? null,
          allow_break_glass: values.allow_break_glass,
        });
      }
      return updateDeploymentEnvironment(pipelineId, (modalFor as DeploymentEnvironment).id, {
        name: values.name,
        sort_order: values.sort_order,
        require_review: values.require_review,
        required_approvals: values.required_approvals ?? null,
        clear_required_approvals: values.required_approvals == null,
        review_plan_id: values.review_plan_id ?? null,
        clear_review_plan: values.review_plan_id == null,
        allow_break_glass: values.allow_break_glass,
      });
    },
    onSuccess: () => {
      message.success(
        modalFor === 'create'
          ? t('deploygov.settings.envCreated')
          : t('deploygov.settings.envUpdated'),
      );
      setModalFor(null);
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const deleteMutation = useMutation({
    mutationFn: (environmentId: string) =>
      deleteDeploymentEnvironment(pipelineId, environmentId),
    onSuccess: () => {
      message.success(t('deploygov.settings.envDeleted'));
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const planName = (id: string | null) =>
    id == null
      ? t('deploygov.settings.envInherited')
      : reviewPlansQuery.data?.find((p) => p.id === id)?.name ?? id;

  const columns: TableColumnsType<DeploymentEnvironment> = [
    { title: t('deploygov.settings.envSortOrder'), dataIndex: 'sort_order', width: 80 },
    { title: t('deploygov.settings.envName'), dataIndex: 'name', width: 180 },
    {
      title: t('deploygov.settings.envRequireReview'),
      dataIndex: 'require_review',
      width: 130,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : '—'),
    },
    {
      title: t('deploygov.settings.envRequiredApprovals'),
      dataIndex: 'required_approvals',
      width: 150,
      align: 'center' as const,
      render: (v: number | null) => v ?? <span className="muted">{t('deploygov.settings.envInherited')}</span>,
    },
    {
      title: t('deploygov.settings.envReviewPlan'),
      dataIndex: 'review_plan_id',
      width: 160,
      render: (v: string | null) => planName(v),
    },
    {
      title: t('deploygov.settings.envAllowBreakGlass'),
      dataIndex: 'allow_break_glass',
      width: 120,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : '—'),
    },
    {
      title: '',
      key: 'actions',
      width: 160,
      render: (_v, row) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button size="small" onClick={() => setModalFor(row)}>
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('deploygov.settings.envDeleteConfirm')}
            okText={t('common.delete')}
            cancelText={t('common.cancel')}
            onConfirm={() => deleteMutation.mutate(row.id)}
          >
            <Button size="small" danger>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 860 }}>
      <div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalFor('create')}>
          {t('deploygov.settings.envAdd')}
        </Button>
      </div>
      <Table<DeploymentEnvironment>
        rowKey="id"
        size="small"
        pagination={false}
        loading={envsQuery.isLoading}
        dataSource={envsQuery.data ?? []}
        locale={{ emptyText: t('deploygov.settings.envEmpty') }}
        columns={columns}
      />
      <Modal
        open={modalFor !== null}
        title={
          modalFor === 'create'
            ? t('deploygov.settings.envCreateTitle')
            : t('deploygov.settings.envEditTitle')
        }
        onCancel={() => setModalFor(null)}
        okText={t('common.save')}
        confirmLoading={saveMutation.isPending}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form<EnvironmentFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          {/* Parity with Create/UpdateDeploymentEnvironmentRequest:
              name @NotBlank @Size(max 255), required_approvals @Min(1). */}
          <Form.Item
            name="name"
            label={t('deploygov.settings.envName')}
            rules={[{ required: true, whitespace: true, max: 255 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="sort_order" label={t('deploygov.settings.envSortOrder')}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="require_review"
            label={t('deploygov.settings.envRequireReview')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
          <Form.Item
            name="required_approvals"
            label={t('deploygov.settings.envRequiredApprovals')}
            rules={[{ type: 'number', min: 1 }]}
          >
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              placeholder={t('deploygov.settings.envInherited')}
            />
          </Form.Item>
          <Form.Item name="review_plan_id" label={t('deploygov.settings.envReviewPlan')}>
            <Select
              allowClear
              placeholder={t('deploygov.settings.envInherited')}
              loading={reviewPlansQuery.isLoading}
              options={(reviewPlansQuery.data ?? []).map((p) => ({ value: p.id, label: p.name }))}
            />
          </Form.Item>
          <Form.Item
            name="allow_break_glass"
            label={t('deploygov.settings.envAllowBreakGlass')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
