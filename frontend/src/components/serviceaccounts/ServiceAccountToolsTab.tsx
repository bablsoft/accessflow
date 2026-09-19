import { useEffect } from 'react';
import { Alert, Button, Checkbox, Form, Radio, Space, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { listMcpTools, serviceAccountKeys } from '@/api/serviceAccounts';
import type { ServiceAccount } from '@/types/api';
import { mcpToolDescription } from '@/utils/enumLabels';
import {
  toolsFormFromAccount,
  toolsUpdateInput,
  type ToolsFormValues,
} from '@/pages/admin/service-accounts/serviceAccountForm';
import { useServiceAccountUpdate } from './useServiceAccountUpdate';

export function ServiceAccountToolsTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<ToolsFormValues>();
  const mode = Form.useWatch('mode', form) ?? toolsFormFromAccount(account).mode;
  const selected = Form.useWatch('tools', form) ?? toolsFormFromAccount(account).tools;
  const catalogQuery = useQuery({ queryKey: serviceAccountKeys.mcpTools(), queryFn: listMcpTools });
  const saveMutation = useServiceAccountUpdate(account.id, t('admin.service_accounts.tools.success'));

  useEffect(() => {
    form.setFieldsValue(toolsFormFromAccount(account));
  }, [account, form]);

  return (
    <div style={{ maxWidth: 640 }}>
      <Typography.Paragraph type="secondary">{t('admin.service_accounts.tools.description')}</Typography.Paragraph>
      <Alert
        type="info"
        showIcon
        data-testid="tools-enforcement-note"
        style={{ marginBottom: 16 }}
        title={t('admin.service_accounts.tools.enforcement_note')}
      />
      {catalogQuery.isError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} title={t('admin.service_accounts.tools.catalog_error')} />
      )}
      <Form<ToolsFormValues>
        form={form}
        name="serviceAccountTools"
        layout="vertical"
        onFinish={(values) => saveMutation.mutate(toolsUpdateInput(values))}
      >
        <Form.Item name="mode">
          <Radio.Group
            options={[
              { value: 'ALL', label: t('admin.service_accounts.tools.mode_all') },
              { value: 'RESTRICTED', label: t('admin.service_accounts.tools.mode_restricted') },
            ]}
          />
        </Form.Item>
        {mode === 'RESTRICTED' && (
          <>
            {selected.length === 0 && (
              <Alert
                type="warning"
                showIcon
                data-testid="tools-none-warning"
                style={{ marginBottom: 12 }}
                title={t('admin.service_accounts.tools.none_selected_warning')}
              />
            )}
            <Form.Item name="tools">
              <Checkbox.Group style={{ width: '100%' }}>
                <Space orientation="vertical" size={6}>
                  {(catalogQuery.data ?? []).map((tool) => {
                    const description = mcpToolDescription(t, tool);
                    return (
                      <Checkbox key={tool} value={tool}>
                        <Typography.Text code>{tool}</Typography.Text>
                        {description && (
                          <span className="muted" style={{ marginLeft: 8 }}>
                            {description}
                          </span>
                        )}
                      </Checkbox>
                    );
                  })}
                </Space>
              </Checkbox.Group>
            </Form.Item>
          </>
        )}
        <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
          {t('admin.service_accounts.tools.save')}
        </Button>
      </Form>
    </div>
  );
}
