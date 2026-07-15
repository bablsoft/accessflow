import { useEffect } from 'react';
import { App, Button, Form, Input, Skeleton, Switch } from 'antd';
import { ApiOutlined, CheckOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  getLangfuseConfig,
  langfuseConfigKeys,
  testLangfuseConfig,
  updateLangfuseConfig,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { UpdateLangfuseConfigInput } from '@/types/api';

const MASK = '********';

interface LangfuseFormValues {
  enabled: boolean;
  host?: string;
  public_key?: string;
  secret_key?: string;
  tracing_enabled: boolean;
  prompt_management_enabled: boolean;
}

export function LangfuseConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<LangfuseFormValues>();

  const cfgQuery = useQuery({
    queryKey: langfuseConfigKeys.current(),
    queryFn: getLangfuseConfig,
  });

  useEffect(() => {
    if (cfgQuery.data) {
      form.setFieldsValue({
        enabled: cfgQuery.data.enabled,
        host: cfgQuery.data.host ?? '',
        public_key: cfgQuery.data.public_key ?? '',
        secret_key: cfgQuery.data.secret_key ?? '',
        tracing_enabled: cfgQuery.data.tracing_enabled,
        prompt_management_enabled: cfgQuery.data.prompt_management_enabled,
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateLangfuseConfigInput) => updateLangfuseConfig(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: langfuseConfigKeys.all });
      message.success(t('admin.langfuse.save_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: testLangfuseConfig,
    onSuccess: (result) => {
      if (result.status === 'OK') {
        message.success(result.message);
      } else {
        message.error(result.message);
      }
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const onFinish = (values: LangfuseFormValues) => {
    saveMutation.mutate({
      enabled: values.enabled,
      host: blankToNull(values.host),
      public_key: blankToNull(values.public_key),
      // If the user did not edit the masked value, leave it on the server.
      secret_key: values.secret_key === MASK ? undefined : blankToNull(values.secret_key),
      tracing_enabled: values.tracing_enabled,
      prompt_management_enabled: values.prompt_management_enabled,
    });
  };

  if (cfgQuery.isLoading) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  }
  if (cfgQuery.isError) {
    return (
      <EmptyState
        title={t('admin.langfuse.load_error')}
        description={adminErrorMessage(cfgQuery.error)}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-langfuse"
        title={t('admin.langfuse.title')}
        subtitle={t('admin.langfuse.subtitle')}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
        <Form<LangfuseFormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Section title={t('admin.langfuse.section_connection')}>
            <Form.Item name="enabled" label={t('admin.langfuse.label_enabled')} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item
              name="host"
              label={t('admin.langfuse.label_host')}
              extra={t('admin.langfuse.host_help')}
              rules={[
                { type: 'url', message: t('admin.langfuse.error_host_invalid') },
                { max: 500 },
              ]}
            >
              <Input className="mono" maxLength={500} placeholder="https://cloud.langfuse.com" />
            </Form.Item>
            <Grid>
              <Form.Item
                name="public_key"
                label={t('admin.langfuse.label_public_key')}
                rules={[{ max: 255 }]}
              >
                <Input className="mono" maxLength={255} autoComplete="off" />
              </Form.Item>
              <Form.Item
                name="secret_key"
                label={t('admin.langfuse.label_secret_key')}
                extra={t('admin.langfuse.secret_key_help')}
                rules={[{ max: 512 }]}
              >
                <Input.Password className="mono" maxLength={512} autoComplete="off" />
              </Form.Item>
            </Grid>
          </Section>

          <Section title={t('admin.langfuse.section_features')}>
            <Form.Item
              name="tracing_enabled"
              label={t('admin.langfuse.label_tracing_enabled')}
              extra={t('admin.langfuse.tracing_help')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="prompt_management_enabled"
              label={t('admin.langfuse.label_prompt_management_enabled')}
              extra={t('admin.langfuse.prompt_management_help')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </Section>

          <div
            style={{
              display: 'flex',
              gap: 8,
              paddingTop: 16,
              borderTop: '1px solid var(--border)',
            }}
          >
            <Button
              type="primary"
              icon={<CheckOutlined />}
              htmlType="submit"
              loading={saveMutation.isPending}
            >
              {t('admin.langfuse.save_button')}
            </Button>
            <Button
              icon={<ApiOutlined />}
              onClick={() => testMutation.mutate()}
              loading={testMutation.isPending}
            >
              {t('admin.langfuse.test_button')}
            </Button>
          </div>
        </Form>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 32 }}>
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 14 }}>{title}</div>
      {children}
    </div>
  );
}

function Grid({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>{children}</div>
  );
}

function blankToNull(s: string | undefined | null): string | null {
  if (s === undefined || s === null) return null;
  return s.trim().length === 0 ? null : s.trim();
}

export default LangfuseConfigPage;
