import { App, Button, Form, Skeleton, Switch } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  getGovernanceDomains,
  governanceDomainKeys,
  setupProgressKeys,
  updateGovernanceDomains,
} from '@/api/admin';
import { useAuthStore } from '@/store/authStore';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { GovernanceDomainsConfig } from '@/types/api';

/**
 * The two governance-domain switches (#926) an organization admin can flip for their own tenant —
 * previously reachable only through the platform-admin organization page. Turning a domain off
 * hides its navigation, review tabs and dashboard widgets; it revokes nothing, and every route
 * and deep link keeps working for anyone who still holds the permission.
 */
export function GovernanceDomainsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<GovernanceDomainsConfig>();
  const patchUser = useAuthStore((s) => s.patchUser);

  const domainsQuery = useQuery({
    queryKey: governanceDomainKeys.current(),
    queryFn: getGovernanceDomains,
  });

  useEffect(() => {
    if (domainsQuery.data) {
      form.setFieldsValue(domainsQuery.data);
    }
  }, [domainsQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (input: GovernanceDomainsConfig) => updateGovernanceDomains(input),
    onSuccess: (saved) => {
      queryClient.setQueryData(governanceDomainKeys.current(), saved);
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.all });
      // The session payload only refreshes on token refresh, so push the new values into the
      // cached user: the sidebar, review tabs and dashboard react on this render.
      patchUser({
        governs_apis: saved.governs_apis,
        governs_deployments: saved.governs_deployments,
      });
      message.success(t('admin.governance_domains.save_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  if (domainsQuery.isError) {
    return (
      <EmptyState
        title={t('admin.governance_domains.load_error')}
        description={adminErrorMessage(domainsQuery.error)}
      />
    );
  }
  if (domainsQuery.isLoading || !domainsQuery.data) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 4 }} />
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.governance_domains.title')}
        subtitle={t('admin.governance_domains.subtitle')}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
        <p className="muted" style={{ maxWidth: 640 }}>
          {t('admin.governance_domains.description')}
        </p>
        <Form<GovernanceDomainsConfig>
          form={form}
          layout="horizontal"
          onFinish={(values) => saveMutation.mutate(values)}
          initialValues={domainsQuery.data}
          style={{ maxWidth: 640 }}
        >
          <Form.Item
            name="governs_apis"
            valuePropName="checked"
            label={t('admin.governance_domains.label_apis')}
            extra={t('admin.governance_domains.help_apis')}
          >
            <Switch aria-label={t('admin.governance_domains.label_apis')} />
          </Form.Item>
          <Form.Item
            name="governs_deployments"
            valuePropName="checked"
            label={t('admin.governance_domains.label_deployments')}
            extra={t('admin.governance_domains.help_deployments')}
          >
            <Switch aria-label={t('admin.governance_domains.label_deployments')} />
          </Form.Item>
          <div style={{ paddingTop: 16, borderTop: '1px solid var(--border)' }}>
            <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
              {t('admin.governance_domains.save_button')}
            </Button>
          </div>
        </Form>
      </div>
    </div>
  );
}
