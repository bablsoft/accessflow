import { Form, InputNumber, Select, Switch } from 'antd';
import type { FormInstance } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Section } from '@/components/layout';
import { aiConfigKeys, listAiConfigs } from '@/api/admin';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import {
  BYTES_CAP_MISSING_ESTIMATE_ACTIONS,
  aiProviderLabel,
  bytesCapMissingEstimateLabel,
} from '@/utils/enumLabels';
import { BYTES_CAP_MIN, supportsBytesCap } from '@/utils/bytesCap';
import { BytesInput } from '@/components/common/BytesInput';
import {
  datasourceEnvironmentOptions,
  toEnvironmentFormValue,
  type DatasourceEnvironmentFormValue,
} from '@/utils/datasourceEnvironment';
import type { BytesCapMissingEstimateAction, Datasource } from '@/types/api';

export interface SettingsFormValues {
  connection_pool_size: number;
  max_rows_per_query: number;
  max_bytes_scanned_per_query?: number | null;
  bytes_cap_missing_estimate?: BytesCapMissingEstimateAction;
  review_plan_id: string | null;
  environment: DatasourceEnvironmentFormValue;
  require_review_reads: boolean;
  require_review_writes: boolean;
  ai_analysis_enabled: boolean;
  ai_config_id: string | null;
  text_to_sql_enabled: boolean;
}

interface SettingsStepProps {
  form: FormInstance<SettingsFormValues>;
  formId: string;
  createdDatasource: Datasource;
  onFinish: (values: SettingsFormValues) => void;
  onValuesChange: () => void;
}

/** Limits, review policy and AI options for the datasource the wizard just created. */
export function SettingsStep({
  form,
  formId,
  createdDatasource,
  onFinish,
  onValuesChange,
}: SettingsStepProps) {
  const { t } = useTranslation();
  const settingsValues = Form.useWatch([], form);
  // The "add AI configuration" link opens another tab, so refresh the list on the way back.
  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
    refetchOnWindowFocus: 'always',
  });
  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });

  return (
    <Form<SettingsFormValues>
      id={formId}
      form={form}
      layout="vertical"
      requiredMark
      onFinish={onFinish}
      onValuesChange={onValuesChange}
      initialValues={{
        connection_pool_size: createdDatasource.connection_pool_size,
        max_rows_per_query: createdDatasource.max_rows_per_query,
        review_plan_id: createdDatasource.review_plan_id ?? null,
        environment: toEnvironmentFormValue(createdDatasource.environment),
        max_bytes_scanned_per_query: createdDatasource.max_bytes_scanned_per_query ?? null,
        bytes_cap_missing_estimate: createdDatasource.bytes_cap_missing_estimate ?? 'REQUIRE_REVIEW',
        require_review_reads: createdDatasource.require_review_reads,
        require_review_writes: createdDatasource.require_review_writes,
        ai_analysis_enabled: createdDatasource.ai_analysis_enabled,
        ai_config_id: createdDatasource.ai_config_id ?? null,
        text_to_sql_enabled: createdDatasource.text_to_sql_enabled,
      }}
    >
      <Section title={t('datasources.create.section_limits')}>
        <div className="af-wizard-fields">
          <Form.Item
            label={t('datasources.create.label_pool_size')}
            name="connection_pool_size"
            extra={t('datasources.create.label_pool_size_help')}
            rules={[{ required: true, type: 'number', min: 1, max: 200 }]}
          >
            <InputNumber className="u-w-full" min={1} max={200} />
          </Form.Item>
          <Form.Item
            label={t('datasources.create.label_max_rows')}
            name="max_rows_per_query"
            extra={t('datasources.create.label_max_rows_help')}
            rules={[{ required: true, type: 'number', min: 1, max: 1_000_000 }]}
          >
            <InputNumber className="u-w-full" min={1} max={1_000_000} />
          </Form.Item>
          {supportsBytesCap(createdDatasource.db_type) && (
            <>
              <Form.Item
                label={t('datasources.settings.label_max_bytes_scanned')}
                name="max_bytes_scanned_per_query"
                extra={t('datasources.settings.max_bytes_scanned_help')}
                rules={[
                  {
                    type: 'number',
                    min: BYTES_CAP_MIN,
                    message: t('datasources.settings.grant_bytes_cap_min'),
                  },
                ]}
              >
                <BytesInput placeholder={t('datasources.settings.max_bytes_scanned_placeholder')} />
              </Form.Item>
              <Form.Item
                label={t('datasources.settings.label_bytes_cap_missing_estimate')}
                name="bytes_cap_missing_estimate"
                extra={t('datasources.settings.bytes_cap_missing_estimate_help')}
              >
                <Select
                  options={BYTES_CAP_MISSING_ESTIMATE_ACTIONS.map((v) => ({
                    value: v,
                    label: bytesCapMissingEstimateLabel(t, v),
                  }))}
                />
              </Form.Item>
            </>
          )}
        </div>
      </Section>
      <Section
        title={t('datasources.create.section_review')}
        description={t('datasources.create.section_review_help')}
      >
        <div className="af-wizard-fields">
          <Form.Item
            label={t('datasources.create.label_review_plan')}
            name="review_plan_id"
            extra={t('datasources.create.review_plan_help')}
          >
            <Select
              allowClear
              loading={reviewPlansQuery.isLoading}
              placeholder={
                reviewPlansQuery.isLoading
                  ? t('datasources.create.review_plan_loading')
                  : t('datasources.create.review_plan_placeholder')
              }
              options={(reviewPlansQuery.data ?? []).map((plan) => ({
                value: plan.id,
                label: plan.name,
              }))}
            />
          </Form.Item>
          <Form.Item
            label={t('datasources.create.label_environment')}
            name="environment"
            extra={t('datasources.create.environment_help')}
          >
            <Select
              options={datasourceEnvironmentOptions(t, t('datasources.create.environment_not_set'))}
            />
          </Form.Item>
        </div>
        <div className="af-wizard-fields">
          <Form.Item
            label={t('datasources.create.label_require_writes')}
            name="require_review_writes"
            extra={t('datasources.create.require_writes_help')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            label={t('datasources.create.label_require_reads')}
            name="require_review_reads"
            extra={t('datasources.create.require_reads_help')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </div>
      </Section>
      <Section
        title={t('datasources.create.section_ai')}
        description={t('datasources.create.section_ai_help')}
      >
        <div className="af-wizard-fields">
          <Form.Item
            label={t('datasources.create.field_ai_analysis_enabled')}
            name="ai_analysis_enabled"
            extra={t('datasources.create.field_ai_analysis_help')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            label={t('datasources.create.field_text_to_sql_enabled')}
            name="text_to_sql_enabled"
            valuePropName="checked"
            extra={t('datasources.create.field_text_to_sql_help')}
          >
            <Switch />
          </Form.Item>
          <Form.Item
            label={t('datasources.create.field_ai_config')}
            name="ai_config_id"
            dependencies={['ai_analysis_enabled', 'text_to_sql_enabled']}
            rules={[
              ({ getFieldValue }) => ({
                required:
                  getFieldValue('ai_analysis_enabled') === true ||
                  getFieldValue('text_to_sql_enabled') === true,
                message: t('datasources.create.field_ai_config_required'),
              }),
            ]}
            extra={
              <a href="/admin/ai-configs/new" target="_blank" rel="noopener noreferrer">
                {t('datasources.create.add_ai_config_link')}
              </a>
            }
          >
            <Select
              allowClear
              disabled={
                !settingsValues?.ai_analysis_enabled && !settingsValues?.text_to_sql_enabled
              }
              placeholder={t('datasources.create.field_ai_config_placeholder')}
              options={(aiConfigsQuery.data ?? []).map((c) => ({
                value: c.id,
                label: `${c.name} · ${aiProviderLabel(t, c.provider)}`,
              }))}
            />
          </Form.Item>
        </div>
      </Section>
    </Form>
  );
}
