import { useEffect } from 'react';
import { App, Button, Empty, Form, Input, Select, Skeleton, Switch, Tabs } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  deploymentPipelineKeys,
  getDeploymentPipeline,
  updateDeploymentPipeline,
} from '@/api/deploymentPipelines';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { aiConfigKeys, listAiConfigs } from '@/api/admin';
import { PIPELINE_PROVIDERS, enumOptions, pipelineProviderLabel } from '@/utils/enumLabels';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { PipelineEnvironmentsTab } from '@/components/deployments/PipelineEnvironmentsTab';
import { PipelinePermissionsTab } from '@/components/deployments/PipelinePermissionsTab';
import { PipelineFreezeWindowsTab } from '@/components/deployments/PipelineFreezeWindowsTab';
import { PipelineRoutingPoliciesTab } from '@/components/deployments/PipelineRoutingPoliciesTab';
import { CiSnippetPanel } from '@/components/deployments/CiSnippetPanel';
import type { DeploymentPipeline, PipelineProvider } from '@/types/api';

interface GeneralFormValues {
  name: string;
  provider: PipelineProvider;
  repository_url?: string | null;
  project_ref?: string | null;
  review_plan_id?: string | null;
  ai_analysis_enabled: boolean;
  ai_config_id?: string | null;
  active: boolean;
}

function GeneralTab({ pipeline }: { pipeline: DeploymentPipeline }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<GeneralFormValues>();
  const aiEnabled = Form.useWatch('ai_analysis_enabled', form) ?? pipeline.ai_analysis_enabled;

  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: () => listReviewPlans(),
  });
  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: () => listAiConfigs(),
  });

  useEffect(() => {
    form.setFieldsValue({
      name: pipeline.name,
      provider: pipeline.provider,
      repository_url: pipeline.repository_url,
      project_ref: pipeline.project_ref,
      review_plan_id: pipeline.review_plan_id,
      ai_analysis_enabled: pipeline.ai_analysis_enabled,
      ai_config_id: pipeline.ai_config_id,
      active: pipeline.active,
    });
  }, [pipeline, form]);

  const saveMutation = useMutation({
    mutationFn: (values: GeneralFormValues) =>
      updateDeploymentPipeline(pipeline.id, {
        name: values.name,
        provider: values.provider,
        // Empty string, not null: the update command treats null as "leave unchanged", so a
        // cleared input would silently keep the old value.
        repository_url: values.repository_url?.trim() ?? '',
        project_ref: values.project_ref?.trim() ?? '',
        review_plan_id: values.review_plan_id ?? null,
        clear_review_plan: values.review_plan_id == null,
        ai_analysis_enabled: values.ai_analysis_enabled,
        ai_config_id: values.ai_analysis_enabled ? values.ai_config_id ?? null : null,
        clear_ai_config: !values.ai_analysis_enabled || values.ai_config_id == null,
        active: values.active,
      }),
    onSuccess: (updated) => {
      message.success(t('deploygov.pipelines.updateSuccess'));
      queryClient.setQueryData(deploymentPipelineKeys.detail(pipeline.id), updated);
      void queryClient.invalidateQueries({ queryKey: deploymentPipelineKeys.lists() });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  return (
    <Form<GeneralFormValues>
      form={form}
      layout="vertical"
      style={{ maxWidth: 520 }}
      onFinish={(values) => saveMutation.mutate(values)}
    >
      {/* Parity with UpdateDeploymentPipelineRequest: name @Size(3-255),
          repository_url @Size(max 2048), project_ref @Size(max 512). */}
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
      <Form.Item
        name="active"
        label={t('deploygov.pipelines.labelActive')}
        valuePropName="checked"
      >
        <Switch size="small" />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
        {t('deploygov.pipelines.save')}
      </Button>
    </Form>
  );
}

export function DeploymentPipelineSettingsPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const navigate = useNavigate();

  const pipelineQuery = useQuery({
    queryKey: deploymentPipelineKeys.detail(id),
    queryFn: () => getDeploymentPipeline(id),
    enabled: !!id,
  });
  const pipeline = pipelineQuery.data;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="guide-deployment-approval"
        title={pipeline?.name ?? t('deploygov.pipelines.title')}
        subtitle={pipeline ? pipelineProviderLabel(t, pipeline.provider) : undefined}
        actions={
          <Button onClick={() => navigate('/admin/deployment-pipelines')}>
            {t('common.back')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '12px 28px' }}>
        {pipelineQuery.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}
        {!pipelineQuery.isLoading && !pipeline && (
          <Empty description={t('deploygov.pipelines.notFound')} />
        )}
        {pipeline && (
          <Tabs
            items={[
              {
                key: 'general',
                label: t('deploygov.settings.tabGeneral'),
                children: <GeneralTab pipeline={pipeline} />,
              },
              {
                key: 'environments',
                label: t('deploygov.settings.tabEnvironments'),
                children: <PipelineEnvironmentsTab pipelineId={pipeline.id} />,
              },
              {
                key: 'permissions',
                label: t('deploygov.settings.tabPermissions'),
                children: <PipelinePermissionsTab pipelineId={pipeline.id} />,
              },
              {
                key: 'freeze-windows',
                label: t('deploygov.settings.tabFreezeWindows'),
                children: <PipelineFreezeWindowsTab pipelineId={pipeline.id} />,
              },
              {
                key: 'routing-policies',
                label: t('deploygov.settings.tabRoutingPolicies'),
                children: <PipelineRoutingPoliciesTab pipelineId={pipeline.id} />,
              },
              {
                key: 'ci',
                label: t('deploygov.settings.tabCi'),
                children: <CiSnippetPanel pipeline={pipeline} />,
              },
            ]}
          />
        )}
      </div>
    </div>
  );
}
