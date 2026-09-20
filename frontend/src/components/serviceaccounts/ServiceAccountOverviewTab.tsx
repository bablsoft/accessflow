import { useEffect } from 'react';
import { App, Button, Descriptions, Form, Input, Select, Switch } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { listRoles, roleKeys } from '@/api/roles';
import { renderUserOption } from '@/components/common/renderUserOption';
import type { ServiceAccount } from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import {
  UPDATE_FORM_CONSTRAINTS,
  fieldRules,
  isBootstrapManaged,
  overviewFormFromAccount,
  overviewUpdateInput,
  type OverviewFormValues,
} from '@/pages/admin/service-accounts/serviceAccountForm';
import { RoleField } from './RoleField';
import { useHumanUserOptions } from './useHumanUsers';
import { useServiceAccountUpdate } from './useServiceAccountUpdate';

export function ServiceAccountOverviewTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<OverviewFormValues>();
  const selectedRoleId = Form.useWatch('role_id', form);
  const bootstrap = isBootstrapManaged(account);

  const rolesQuery = useQuery({ queryKey: roleKeys.lists(), queryFn: listRoles });
  const owners = useHumanUserOptions(true);
  const saveMutation = useServiceAccountUpdate(account.id, t('admin.service_accounts.overview.success'));

  useEffect(() => {
    form.setFieldsValue(overviewFormFromAccount(account));
  }, [account, form]);

  const ownerOptions =
    account.owner_user_id && !owners.options.some((o) => o.value === account.owner_user_id)
      ? [
          {
            value: account.owner_user_id,
            label: account.owner_email ?? account.owner_user_id,
            principal_type: 'HUMAN' as const,
          },
          ...owners.options,
        ]
      : owners.options;

  return (
    <div style={{ maxWidth: 560 }}>
      <Form<OverviewFormValues>
        form={form}
        name="serviceAccountOverview"
        layout="vertical"
        onFinish={(values) => {
          const input = overviewUpdateInput(values, account);
          if (Object.keys(input).length === 0) {
            message.info(t('admin.service_accounts.overview.nothing_changed'));
            return;
          }
          saveMutation.mutate(input);
        }}
      >
        <Form.Item label={t('admin.service_accounts.overview.label_email')}>
          <Input value={account.email} disabled data-testid="overview-email" />
        </Form.Item>
        {/* Rules derive from UPDATE_FORM_CONSTRAINTS (createFormParity.test.ts). A bootstrap
            account's declared fields are disabled here and stripped by overviewUpdateInput. */}
        <Form.Item
          name="display_name"
          label={t('admin.service_accounts.overview.label_display_name')}
          extra={bootstrap ? t('admin.service_accounts.overview.bootstrap_readonly') : undefined}
          rules={fieldRules(t, { ...UPDATE_FORM_CONSTRAINTS.display_name, required: true })}
        >
          <Input disabled={bootstrap} />
        </Form.Item>
        <RoleField
          roles={rolesQuery.data ?? []}
          loading={rolesQuery.isLoading}
          selectedRoleId={selectedRoleId ?? account.role_id}
          disabled={bootstrap}
          extra={bootstrap ? t('admin.service_accounts.overview.bootstrap_readonly') : undefined}
        />
        <Form.Item
          name="owner_user_id"
          label={t('admin.service_accounts.overview.label_owner')}
          extra={t('admin.service_accounts.create_modal.owner_help')}
        >
          <Select
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            placeholder={t('admin.service_accounts.overview.owner_placeholder')}
            loading={owners.loading}
            options={ownerOptions}
            optionRender={renderUserOption}
          />
        </Form.Item>
        <Form.Item
          name="description"
          label={t('admin.service_accounts.overview.label_description')}
          rules={fieldRules(t, UPDATE_FORM_CONSTRAINTS.description)}
        >
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item
          name="active"
          label={t('admin.service_accounts.overview.label_active')}
          valuePropName="checked"
          extra={t('admin.service_accounts.overview.active_help')}
        >
          <Switch size="small" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
          {t('admin.service_accounts.overview.save')}
        </Button>
      </Form>
      <Descriptions size="small" column={1} style={{ marginTop: 24 }}>
        <Descriptions.Item label={t('admin.service_accounts.overview.label_created')}>
          {fmtDate(account.created_at)}
        </Descriptions.Item>
        <Descriptions.Item label={t('admin.service_accounts.overview.label_updated')}>
          {fmtDate(account.updated_at)}
        </Descriptions.Item>
        <Descriptions.Item label={t('admin.service_accounts.overview.label_last_used')}>
          {account.last_used_at ? fmtDate(account.last_used_at) : t('admin.service_accounts.never_used')}
        </Descriptions.Item>
      </Descriptions>
    </div>
  );
}
