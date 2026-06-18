import { useEffect } from 'react';
import {
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Progress,
  Skeleton,
  Space,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  getOrganization,
  getOrganizationUsage,
  organizationKeys,
  updateOrganization,
} from '@/api/organizations';
import { organizationErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { formatQuota } from '@/utils/formatQuota';
import type { OrganizationUsage, UpdateOrganizationInput } from '@/types/api';

interface EditFormValues {
  name: string;
  max_datasources?: number | null;
  max_users?: number | null;
  max_queries_per_day?: number | null;
}

export function OrganizationDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [form] = Form.useForm<EditFormValues>();

  const orgQuery = useQuery({
    queryKey: organizationKeys.detail(id),
    queryFn: () => getOrganization(id),
    enabled: !!id,
  });

  const usageQuery = useQuery({
    queryKey: organizationKeys.usage(id),
    queryFn: () => getOrganizationUsage(id),
    enabled: !!id,
  });

  useEffect(() => {
    if (orgQuery.data) {
      form.setFieldsValue({
        name: orgQuery.data.name,
        max_datasources: orgQuery.data.max_datasources,
        max_users: orgQuery.data.max_users,
        max_queries_per_day: orgQuery.data.max_queries_per_day,
      });
    }
  }, [orgQuery.data, form]);

  const updateMutation = useMutation({
    mutationFn: (payload: UpdateOrganizationInput) => updateOrganization(id, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: organizationKeys.all });
      message.success(t('admin.organizations.update_success'));
    },
    onError: (err) => showApiError(message, err, organizationErrorMessage),
  });

  const onSave = (values: EditFormValues) => {
    updateMutation.mutate({
      name: values.name.trim(),
      max_datasources: values.max_datasources ?? 0,
      max_users: values.max_users ?? 0,
      max_queries_per_day: values.max_queries_per_day ?? 0,
    });
  };

  if (orgQuery.isLoading) {
    return <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 24 }} />;
  }
  if (orgQuery.isError || !orgQuery.data) {
    return (
      <EmptyState
        title={t('admin.organizations.load_error')}
        description={organizationErrorMessage(orgQuery.error)}
        action={
          <Button onClick={() => navigate('/admin/organizations')}>
            {t('admin.organizations.back_to_list')}
          </Button>
        }
      />
    );
  }

  const org = orgQuery.data;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={org.name}
        subtitle={org.slug}
        actions={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/admin/organizations')}>
            {t('admin.organizations.back_to_list')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px', maxWidth: 720 }}>
        <Card title={t('admin.organizations.usage_title')} style={{ marginBottom: 16 }}>
          {usageQuery.isLoading || !usageQuery.data ? (
            <Skeleton active paragraph={{ rows: 3 }} />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <UsageBar
                label={t('admin.organizations.usage_datasources')}
                used={usageQuery.data.datasource_count}
                limit={usageQuery.data.max_datasources}
                unlimitedLabel={formatQuota(t, usageQuery.data.max_datasources)}
              />
              <UsageBar
                label={t('admin.organizations.usage_users')}
                used={usageQuery.data.user_count}
                limit={usageQuery.data.max_users}
                unlimitedLabel={formatQuota(t, usageQuery.data.max_users)}
              />
              <UsageBar
                label={t('admin.organizations.usage_queries')}
                used={usageQuery.data.queries_last_24h}
                limit={usageQuery.data.max_queries_per_day}
                unlimitedLabel={formatQuota(t, usageQuery.data.max_queries_per_day)}
              />
            </Space>
          )}
        </Card>

        <Card title={t('admin.organizations.settings_title')}>
          <Form<EditFormValues> form={form} layout="vertical" onFinish={onSave}>
            <Form.Item
              name="name"
              label={t('admin.organizations.label_name')}
              rules={[{ required: true, min: 1, max: 255, whitespace: true }]}
            >
              <Input maxLength={255} />
            </Form.Item>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Form.Item
                name="max_datasources"
                label={t('admin.organizations.label_max_datasources')}
                rules={[{ type: 'number', min: 0 }]}
                extra={t('admin.organizations.quota_help')}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="max_users"
                label={t('admin.organizations.label_max_users')}
                rules={[{ type: 'number', min: 0 }]}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="max_queries_per_day"
                label={t('admin.organizations.label_max_queries')}
                rules={[{ type: 'number', min: 0 }]}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </div>
            <Button type="primary" htmlType="submit" loading={updateMutation.isPending}>
              {t('admin.organizations.save_update')}
            </Button>
          </Form>
        </Card>
      </div>
    </div>
  );
}

function UsageBar({
  label, used, limit, unlimitedLabel,
}: {
  label: string;
  used: OrganizationUsage['datasource_count'];
  limit: number | null;
  unlimitedLabel: string;
}) {
  const hasLimit = limit !== null && limit > 0;
  const percent = hasLimit ? Math.min(100, Math.round((used / limit) * 100)) : 0;
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <span>{label}</span>
        <span className="mono muted">
          {used} / {unlimitedLabel}
        </span>
      </div>
      <Progress
        percent={percent}
        showInfo={false}
        status={hasLimit && percent >= 100 ? 'exception' : 'normal'}
      />
    </div>
  );
}
