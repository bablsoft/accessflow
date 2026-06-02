import { useEffect } from 'react';
import { App, Button, Form, Input, Popconfirm, Skeleton, Switch, Typography } from 'antd';
import { CheckOutlined, DeleteOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  deleteSlackAppConfig,
  getSlackAppConfig,
  slackAppConfigKeys,
  testSlackAppConfig,
  upsertSlackAppConfig,
} from '@/api/slack';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { UpsertSlackAppConfigInput } from '@/types/api';

const MASK = '********';

interface SlackFormValues {
  app_id: string;
  default_channel_id: string;
  bot_token?: string;
  signing_secret?: string;
  active: boolean;
}

export function SlackConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<SlackFormValues>();

  const cfgQuery = useQuery({
    queryKey: slackAppConfigKeys.current(),
    queryFn: getSlackAppConfig,
  });

  const configured = Boolean(cfgQuery.data);

  useEffect(() => {
    const cfg = cfgQuery.data;
    if (cfg) {
      form.setFieldsValue({
        app_id: cfg.app_id,
        default_channel_id: cfg.default_channel_id,
        bot_token: cfg.has_bot_token ? MASK : '',
        signing_secret: cfg.has_signing_secret ? MASK : '',
        active: cfg.active,
      });
    } else if (cfg === null) {
      form.setFieldsValue({ active: true });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpsertSlackAppConfigInput) => upsertSlackAppConfig(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: slackAppConfigKeys.all });
      message.success(t('admin.slack.save_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: testSlackAppConfig,
    onSuccess: (result) => {
      if (result.status === 'OK') {
        message.success(t('admin.slack.test_success'));
      } else {
        message.error(t('admin.slack.test_failed', { detail: result.detail }));
      }
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteSlackAppConfig,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: slackAppConfigKeys.all });
      form.resetFields();
      message.success(t('admin.slack.delete_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const onFinish = (values: SlackFormValues) => {
    saveMutation.mutate({
      app_id: values.app_id.trim(),
      default_channel_id: values.default_channel_id.trim(),
      bot_token: secretOrUndefined(values.bot_token),
      signing_secret: secretOrUndefined(values.signing_secret),
      active: values.active,
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
        title={t('admin.slack.load_error')}
        description={adminErrorMessage(cfgQuery.error)}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('admin.slack.title')} subtitle={t('admin.slack.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
        <Typography.Paragraph type="secondary">
          {configured ? t('admin.slack.status_configured') : t('admin.slack.status_unconfigured')}
        </Typography.Paragraph>
        <Form<SlackFormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="app_id"
            label={t('admin.slack.label_app_id')}
            rules={[
              { required: true, message: t('validation.slack.app_id_required') },
              { max: 64, message: t('validation.slack.app_id_max') },
            ]}
          >
            <Input className="mono" maxLength={64} placeholder="A0123456789" />
          </Form.Item>
          <Form.Item
            name="default_channel_id"
            label={t('admin.slack.label_default_channel')}
            rules={[
              { required: true, message: t('validation.slack.default_channel_required') },
              { max: 64, message: t('validation.slack.default_channel_max') },
            ]}
          >
            <Input className="mono" maxLength={64} placeholder="C0123456789" />
          </Form.Item>
          <Form.Item
            name="bot_token"
            label={t('admin.slack.label_bot_token')}
            rules={[
              { required: !configured, message: t('validation.slack.bot_token_required') },
              { max: 512, message: t('validation.slack.bot_token_max') },
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder={configured ? MASK : 'xoxb-…'}
            />
          </Form.Item>
          <Form.Item
            name="signing_secret"
            label={t('admin.slack.label_signing_secret')}
            rules={[
              { required: !configured, message: t('validation.slack.signing_secret_required') },
              { max: 255, message: t('validation.slack.signing_secret_max') },
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder={configured ? MASK : ''} />
          </Form.Item>
          <Form.Item name="active" label={t('admin.slack.label_active')} valuePropName="checked">
            <Switch />
          </Form.Item>

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
              {t('admin.slack.save_button')}
            </Button>
            <Button
              icon={<SendOutlined />}
              disabled={!configured}
              loading={testMutation.isPending}
              onClick={() => testMutation.mutate()}
            >
              {t('admin.slack.test_button')}
            </Button>
            {configured && (
              <Popconfirm
                title={t('admin.slack.delete_confirm')}
                okText={t('admin.slack.delete_button')}
                cancelText={t('common.cancel')}
                onConfirm={() => deleteMutation.mutate()}
              >
                <Button danger icon={<DeleteOutlined />} loading={deleteMutation.isPending}>
                  {t('admin.slack.delete_button')}
                </Button>
              </Popconfirm>
            )}
          </div>
        </Form>
      </div>
    </div>
  );
}

function secretOrUndefined(value: string | undefined): string | undefined {
  if (value === undefined || value === MASK) {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed.length === 0 ? undefined : trimmed;
}

export default SlackConfigPage;
