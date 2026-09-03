import { useState } from 'react';
import { App, Button, Form, Input, Modal, Popconfirm, Select, Skeleton, Switch, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  createDeploymentPipeline,
  deleteDeploymentPipeline,
  deploymentPipelineKeys,
  listDeploymentPipelines,
} from '@/api/deploymentPipelines';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { aiConfigKeys, listAiConfigs } from '@/api/admin';
import { PIPELINE_PROVIDERS, enumOptions, pipelineProviderLabel } from '@/utils/enumLabels';
import { fmtDate } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { PipelineIdCopy } from '@/components/deployments/PipelineIdCopy';
import type { CreateDeploymentPipelineInput, DeploymentPipeline } from '@/types/api';

const PAGE_SIZE = 20;

interface CreateFormValues {
  name: string;
  provider: DeploymentPipeline['provider'];
  repository_url?: string;
  project_ref?: string;
  review_plan_id?: string | null;
  ai_analysis_enabled: boolean;
  ai_config_id?: string | null;
}

export function DeploymentPipelinesPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<CreateFormValues>();
  const aiEnabled = Form.useWatch('ai_analysis_enabled', form) ?? true;
  const [page, setPage] = useState(0);

  const listQuery = useQuery({
    queryKey: deploymentPipelineKeys.list({ page, size: PAGE_SIZE }),
    queryFn: () => listDeploymentPipelines({ page, size: PAGE_SIZE }),
  });
  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: () => listReviewPlans(),
    enabled: createOpen,
  });
  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: () => listAiConfigs(),
    enabled: createOpen,
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: deploymentPipelineKeys.lists() });

  const createMutation = useMutation({
    mutationFn: (values: CreateFormValues) => {
      const input: CreateDeploymentPipelineInput = {
        name: values.name,
        provider: values.provider,
        repository_url: values.repository_url?.trim() || null,
        project_ref: values.project_ref?.trim() || null,
        review_plan_id: values.review_plan_id ?? null,
        ai_analysis_enabled: values.ai_analysis_enabled,
        ai_config_id: values.ai_analysis_enabled ? values.ai_config_id ?? null : null,
      };
      return createDeploymentPipeline(input);
    },
    onSuccess: (created) => {
      message.success(t('deploygov.pipelines.createSuccess'));
      setCreateOpen(false);
      form.resetFields();
      void invalidate();
      navigate(`/admin/deployment-pipelines/${created.id}`);
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDeploymentPipeline(id),
    onSuccess: () => {
      message.success(t('deploygov.pipelines.deleteSuccess'));
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const columns: TableColumnsType<DeploymentPipeline> = [
    { title: t('deploygov.pipelines.name'), dataIndex: 'name', width: 200 },
    {
      title: t('deploygov.pipelines.id'),
      dataIndex: 'id',
      width: 130,
      render: (v: string) => <PipelineIdCopy id={v} truncate />,
    },
    {
      title: t('deploygov.pipelines.provider'),
      dataIndex: 'provider',
      width: 170,
      render: (p: DeploymentPipeline['provider']) => pipelineProviderLabel(t, p),
    },
    {
      title: t('deploygov.pipelines.repository'),
      dataIndex: 'repository_url',
      ellipsis: true,
      render: (v: string | null) =>
        v ? (
          <span className="mono" style={{ fontSize: 12 }}>
            {v}
          </span>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      title: t('deploygov.pipelines.aiEnabled'),
      dataIndex: 'ai_analysis_enabled',
      width: 110,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : '—'),
    },
    {
      title: t('deploygov.pipelines.active'),
      dataIndex: 'active',
      width: 100,
      render: (v: boolean) =>
        v ? (
          <Tag color="green">{t('deploygov.pipelines.active')}</Tag>
        ) : (
          <Tag>{t('deploygov.pipelines.inactive')}</Tag>
        ),
    },
    {
      title: t('deploygov.pipelines.created'),
      dataIndex: 'created_at',
      width: 150,
      render: (v: string) => fmtDate(v),
    },
    {
      title: '',
      key: 'actions',
      width: 180,
      // Pinned: with the id column added, an ordinary repository URL pushes the only two row
      // actions past the right edge on a 1440px viewport.
      fixed: 'right' as const,
      render: (_v, row) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            onClick={(e) => {
              e.stopPropagation();
              navigate(`/admin/deployment-pipelines/${row.id}`);
            }}
          >
            {t('deploygov.pipelines.openSettings')}
          </Button>
          <Popconfirm
            title={t('deploygov.pipelines.deleteConfirmTitle')}
            description={t('deploygov.pipelines.deleteConfirmBody')}
            okText={t('common.delete')}
            cancelText={t('common.cancel')}
            onConfirm={() => deleteMutation.mutate(row.id)}
          >
            <Button size="small" danger onClick={(e) => e.stopPropagation()}>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('deploygov.pipelines.title')}
        subtitle={t('deploygov.pipelines.subtitle')}
        docsAnchor="cfg-deployment-pipelines"
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            {t('deploygov.pipelines.add')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '12px' }}>
        {listQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<DeploymentPipeline>
            rowKey="id"
            dataSource={listQuery.data?.content ?? []}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('deploygov.pipelines.empty') }}
            onRow={(row) => ({
              onClick: () => navigate(`/admin/deployment-pipelines/${row.id}`),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: listQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </div>
      <Modal
        open={createOpen}
        title={t('deploygov.pipelines.createTitle')}
        onCancel={() => setCreateOpen(false)}
        okText={t('deploygov.pipelines.create')}
        confirmLoading={createMutation.isPending}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form<CreateFormValues>
          form={form}
          layout="vertical"
          initialValues={{ provider: 'GITHUB_ACTIONS', ai_analysis_enabled: true }}
          onFinish={(values) => createMutation.mutate(values)}
        >
          {/* Parity with CreateDeploymentPipelineRequest: name @NotBlank @Size(3-255),
              provider @NotNull, repository_url @Size(max 2048), project_ref @Size(max 512). */}
          <Form.Item
            name="name"
            label={t('deploygov.pipelines.labelName')}
            rules={[{ required: true, whitespace: true, min: 3, max: 255 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="provider"
            label={t('deploygov.pipelines.labelProvider')}
            rules={[{ required: true }]}
          >
            <Select options={enumOptions(PIPELINE_PROVIDERS, pipelineProviderLabel, t)} />
          </Form.Item>
          <Form.Item
            name="repository_url"
            label={t('deploygov.pipelines.labelRepositoryUrl')}
            rules={[{ max: 2048 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="project_ref"
            label={t('deploygov.pipelines.labelProjectRef')}
            rules={[{ max: 512 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="review_plan_id" label={t('deploygov.pipelines.labelReviewPlan')}>
            <Select
              allowClear
              placeholder={t('deploygov.pipelines.reviewPlanDefault')}
              loading={reviewPlansQuery.isLoading}
              options={(reviewPlansQuery.data ?? []).map((p) => ({ value: p.id, label: p.name }))}
            />
          </Form.Item>
          <Form.Item
            name="ai_analysis_enabled"
            label={t('deploygov.pipelines.labelAiAnalysis')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
          {aiEnabled && (
            <Form.Item name="ai_config_id" label={t('deploygov.pipelines.labelAiConfig')}>
              <Select
                allowClear
                placeholder={t('deploygov.pipelines.aiConfigDefault')}
                loading={aiConfigsQuery.isLoading}
                options={(aiConfigsQuery.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
