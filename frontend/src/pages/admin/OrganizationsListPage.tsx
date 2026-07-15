import { useState } from 'react';
import { App, Button, Form, Input, InputNumber, Modal, Skeleton, Table, Tag } from 'antd';
import { CheckCircleOutlined, PlusOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createOrganization,
  disableOrganization,
  enableOrganization,
  listOrganizations,
  organizationKeys,
} from '@/api/organizations';
import { organizationErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { formatQuota } from '@/utils/formatQuota';
import type { CreateOrganizationInput, Organization } from '@/types/api';

interface CreateFormValues {
  name: string;
  slug?: string;
  max_datasources?: number | null;
  max_users?: number | null;
  max_queries_per_day?: number | null;
}

export function OrganizationsListPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<CreateFormValues>();

  const orgsQuery = useQuery({
    queryKey: organizationKeys.lists(),
    queryFn: () => listOrganizations({ size: 100 }),
  });

  const createMutation = useMutation({
    mutationFn: (payload: CreateOrganizationInput) => createOrganization(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: organizationKeys.all });
      message.success(t('admin.organizations.create_success'));
      setCreating(false);
      form.resetFields();
    },
    onError: (err) => showApiError(message, err, organizationErrorMessage),
  });

  const disableMutation = useMutation({
    mutationFn: (id: string) => disableOrganization(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: organizationKeys.all });
      message.success(t('admin.organizations.disable_success'));
    },
    onError: (err) => showApiError(message, err, organizationErrorMessage),
  });

  const enableMutation = useMutation({
    mutationFn: (id: string) => enableOrganization(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: organizationKeys.all });
      message.success(t('admin.organizations.enable_success'));
    },
    onError: (err) => showApiError(message, err, organizationErrorMessage),
  });

  const onCreate = (values: CreateFormValues) => {
    createMutation.mutate({
      name: values.name.trim(),
      slug: values.slug?.trim() || null,
      max_datasources: values.max_datasources ?? null,
      max_users: values.max_users ?? null,
      max_queries_per_day: values.max_queries_per_day ?? null,
    });
  };

  const onToggleDisabled = (org: Organization) => {
    if (org.disabled) {
      enableMutation.mutate(org.id);
      return;
    }
    modal.confirm({
      title: t('admin.organizations.disable_confirm_title'),
      content: t('admin.organizations.disable_confirm_body', { name: org.name }),
      okType: 'danger',
      okText: t('admin.organizations.disable_action'),
      cancelText: t('common.cancel'),
      onOk: () => disableMutation.mutateAsync(org.id),
    });
  };

  const orgs = orgsQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-organizations"
        title={t('admin.organizations.title')}
        subtitle={t('admin.organizations.subtitle')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => orgsQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('admin.organizations.add_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {orgsQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : orgsQuery.isError ? (
          <EmptyState
            title={t('admin.organizations.load_error')}
            description={organizationErrorMessage(orgsQuery.error)}
          />
        ) : orgs.length === 0 ? (
          <EmptyState
            title={t('admin.organizations.title')}
            description={t('admin.organizations.empty')}
          />
        ) : (
          <Table<Organization>
            rowKey="id"
            size="middle"
            dataSource={orgs}
            scroll={{ x: 'max-content' }}
            pagination={{ pageSize: 20 }}
            columns={[
              {
                title: t('admin.organizations.col_name'),
                dataIndex: 'name',
                render: (v: string, org) => (
                  <Button type="link" style={{ padding: 0, fontWeight: 500 }}
                    onClick={() => navigate(`/admin/organizations/${org.id}`)}>
                    {v}
                  </Button>
                ),
              },
              {
                title: t('admin.organizations.col_slug'),
                dataIndex: 'slug',
                render: (v: string) => <span className="mono muted">{v}</span>,
              },
              {
                title: t('admin.organizations.col_status'),
                dataIndex: 'disabled',
                width: 120,
                render: (disabled: boolean) =>
                  disabled ? (
                    <Tag icon={<StopOutlined />} color="error">
                      {t('admin.organizations.status_disabled')}
                    </Tag>
                  ) : (
                    <Tag icon={<CheckCircleOutlined />} color="success">
                      {t('admin.organizations.status_enabled')}
                    </Tag>
                  ),
              },
              {
                title: t('admin.organizations.col_max_datasources'),
                dataIndex: 'max_datasources',
                width: 120,
                render: (v: number | null) => <span className="mono">{formatQuota(t, v)}</span>,
              },
              {
                title: t('admin.organizations.col_max_users'),
                dataIndex: 'max_users',
                width: 110,
                render: (v: number | null) => <span className="mono">{formatQuota(t, v)}</span>,
              },
              {
                title: t('admin.organizations.col_max_queries'),
                dataIndex: 'max_queries_per_day',
                width: 130,
                render: (v: number | null) => <span className="mono">{formatQuota(t, v)}</span>,
              },
              {
                title: t('admin.organizations.col_actions'),
                width: 160,
                render: (_v, org) => (
                  <Button
                    size="small"
                    danger={!org.disabled}
                    onClick={() => onToggleDisabled(org)}
                  >
                    {org.disabled
                      ? t('admin.organizations.enable_action')
                      : t('admin.organizations.disable_action')}
                  </Button>
                ),
              },
            ]}
          />
        )}
      </div>

      <Modal
        open={creating}
        title={t('admin.organizations.create_modal_title')}
        onCancel={() => setCreating(false)}
        onOk={() => form.submit()}
        okText={t('admin.organizations.save_create')}
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending}
        destroyOnHidden
        width={520}
      >
        <Form<CreateFormValues> form={form} layout="vertical" onFinish={onCreate}>
          <Form.Item
            name="name"
            label={t('admin.organizations.label_name')}
            rules={[{ required: true, min: 1, max: 255, whitespace: true }]}
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="slug"
            label={t('admin.organizations.label_slug')}
            rules={[{ max: 100 }]}
            extra={t('admin.organizations.slug_help')}
          >
            <Input maxLength={100} />
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
        </Form>
      </Modal>
    </div>
  );
}
