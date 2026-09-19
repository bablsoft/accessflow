import { Alert, App, Button, Popconfirm, Skeleton, Space, Tabs, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { ManagedByTag } from '@/components/common/ManagedByTag';
import { ServiceAccountActivityTab } from '@/components/serviceaccounts/ServiceAccountActivityTab';
import { ServiceAccountKeysTab } from '@/components/serviceaccounts/ServiceAccountKeysTab';
import { ServiceAccountLimitsTab } from '@/components/serviceaccounts/ServiceAccountLimitsTab';
import { ServiceAccountOverviewTab } from '@/components/serviceaccounts/ServiceAccountOverviewTab';
import { ServiceAccountPrincipalsTab } from '@/components/serviceaccounts/ServiceAccountPrincipalsTab';
import { ServiceAccountToolsTab } from '@/components/serviceaccounts/ServiceAccountToolsTab';
import {
  deactivateServiceAccount,
  getServiceAccount,
  serviceAccountKeys,
} from '@/api/serviceAccounts';
import { serviceAccountErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { isBootstrapManaged } from './serviceAccountForm';

const TAB_KEYS = ['overview', 'api-keys', 'mcp-tools', 'limits', 'principals', 'activity'] as const;

export function ServiceAccountSettingsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  // The active tab lives in the URL so the docs can deep-link a tab and a reload keeps it.
  const requestedTab = searchParams.get('tab');
  const activeTab = TAB_KEYS.find((key) => key === requestedTab) ?? 'overview';

  const accountQuery = useQuery({
    queryKey: serviceAccountKeys.detail(id),
    queryFn: () => getServiceAccount(id),
    enabled: !!id,
  });
  const account = accountQuery.data;

  const deactivateMutation = useMutation({
    mutationFn: () => deactivateServiceAccount(id),
    onSuccess: () => {
      message.success(t('admin.service_accounts.settings.deactivate_success'));
      void queryClient.invalidateQueries({ queryKey: serviceAccountKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: serviceAccountKeys.lists() });
    },
    onError: (err) => showApiError(message, err, serviceAccountErrorMessage),
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-service-accounts"
        title={account?.display_name ?? t('admin.service_accounts.title')}
        subtitle={
          account ? (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <span className="mono" style={{ fontSize: 12 }}>
                {account.email}
              </span>
              <ManagedByTag managedBy={account.managed_by} />
              {account.active ? (
                <Tag color="green">{t('admin.service_accounts.status_active')}</Tag>
              ) : (
                <Tag>{t('admin.service_accounts.status_inactive')}</Tag>
              )}
            </span>
          ) : undefined
        }
        actions={
          <Space>
            {account?.active && (
              <Popconfirm
                title={t('admin.service_accounts.settings.deactivate_confirm_title')}
                description={t('admin.service_accounts.settings.deactivate_confirm_body')}
                okText={t('admin.service_accounts.settings.deactivate')}
                cancelText={t('common.cancel')}
                okButtonProps={{ danger: true }}
                onConfirm={() => deactivateMutation.mutate()}
              >
                <Button danger loading={deactivateMutation.isPending}>
                  {t('admin.service_accounts.settings.deactivate')}
                </Button>
              </Popconfirm>
            )}
            <Button onClick={() => navigate('/admin/service-accounts')}>
              {t('admin.service_accounts.settings.back')}
            </Button>
          </Space>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '12px 28px' }}>
        {accountQuery.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}
        {accountQuery.isError && (
          // A 500 or a network failure is not "gone": keep the server detail and offer a retry.
          <EmptyState
            title={t('admin.service_accounts.settings.load_error')}
            description={serviceAccountErrorMessage(accountQuery.error)}
            action={<Button onClick={() => accountQuery.refetch()}>{t('common.retry')}</Button>}
          />
        )}
        {!accountQuery.isLoading && !accountQuery.isError && !account && (
          <EmptyState title={t('admin.service_accounts.settings.not_found')} />
        )}
        {account && isBootstrapManaged(account) && (
          <Alert
            type="info"
            showIcon
            data-testid="bootstrap-banner"
            style={{ marginBottom: 16 }}
            title={t('admin.service_accounts.settings.bootstrap_banner_title')}
            description={t('admin.service_accounts.settings.bootstrap_banner_body')}
          />
        )}
        {account && (
          <Tabs
            activeKey={activeTab}
            onChange={(key) =>
              setSearchParams(
                (previous) => {
                  const next = new URLSearchParams(previous);
                  if (key === 'overview') next.delete('tab');
                  else next.set('tab', key);
                  return next;
                },
                { replace: true },
              )
            }
            items={[
              {
                key: 'overview',
                label: t('admin.service_accounts.settings.tab_overview'),
                children: <ServiceAccountOverviewTab account={account} />,
              },
              {
                key: 'api-keys',
                label: t('admin.service_accounts.settings.tab_keys'),
                children: <ServiceAccountKeysTab account={account} />,
              },
              {
                key: 'mcp-tools',
                label: t('admin.service_accounts.settings.tab_tools'),
                children: <ServiceAccountToolsTab account={account} />,
              },
              {
                key: 'limits',
                label: t('admin.service_accounts.settings.tab_limits'),
                children: <ServiceAccountLimitsTab account={account} />,
              },
              {
                key: 'principals',
                label: t('admin.service_accounts.settings.tab_principals'),
                children: <ServiceAccountPrincipalsTab account={account} />,
              },
              {
                key: 'activity',
                label: t('admin.service_accounts.settings.tab_activity'),
                children: <ServiceAccountActivityTab account={account} />,
              },
            ]}
          />
        )}
      </div>
    </div>
  );
}
