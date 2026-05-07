import { useEffect } from 'react';
import { App, Button, Form, Input, Select, Skeleton, Switch } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { getSamlConfig, samlConfigKeys, updateSamlConfig } from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import type { Role, UpdateSamlConfigInput } from '@/types/api';

const MASK = '********';

interface SamlFormValues {
  idp_metadata_url?: string;
  idp_entity_id?: string;
  sp_entity_id?: string;
  acs_url?: string;
  slo_url?: string;
  signing_cert_pem?: string;
  attr_email: string;
  attr_display_name: string;
  attr_role?: string;
  default_role: Role;
  active: boolean;
}

export function SamlConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<SamlFormValues>();

  const cfgQuery = useQuery({
    queryKey: samlConfigKeys.current(),
    queryFn: getSamlConfig,
  });

  useEffect(() => {
    if (cfgQuery.data) {
      form.setFieldsValue({
        idp_metadata_url: cfgQuery.data.idp_metadata_url ?? '',
        idp_entity_id: cfgQuery.data.idp_entity_id ?? '',
        sp_entity_id: cfgQuery.data.sp_entity_id ?? '',
        acs_url: cfgQuery.data.acs_url ?? '',
        slo_url: cfgQuery.data.slo_url ?? '',
        signing_cert_pem: cfgQuery.data.signing_cert_pem ?? '',
        attr_email: cfgQuery.data.attr_email,
        attr_display_name: cfgQuery.data.attr_display_name,
        attr_role: cfgQuery.data.attr_role ?? '',
        default_role: cfgQuery.data.default_role,
        active: cfgQuery.data.active,
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateSamlConfigInput) => updateSamlConfig(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: samlConfigKeys.all });
      message.success(t('admin.saml.save_success'));
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const onFinish = (values: SamlFormValues) => {
    saveMutation.mutate({
      idp_metadata_url: blankToNull(values.idp_metadata_url),
      idp_entity_id: blankToNull(values.idp_entity_id),
      sp_entity_id: blankToNull(values.sp_entity_id),
      acs_url: blankToNull(values.acs_url),
      slo_url: blankToNull(values.slo_url),
      // If the user did not edit the masked value, leave it on the server.
      signing_cert_pem:
        values.signing_cert_pem === MASK ? undefined : blankToNull(values.signing_cert_pem),
      attr_email: values.attr_email,
      attr_display_name: values.attr_display_name,
      attr_role: blankToNull(values.attr_role),
      default_role: values.default_role,
      active: values.active,
    });
  };

  if (cfgQuery.isLoading) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 12 }} />
      </div>
    );
  }
  if (cfgQuery.isError) {
    return (
      <EmptyState
        title={t('admin.saml.load_error')}
        description={adminErrorMessage(cfgQuery.error)}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('admin.saml.title')} subtitle={t('admin.saml.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <Form<SamlFormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Section title={t('admin.saml.section_idp')}>
            <Form.Item
              name="idp_metadata_url"
              label={t('admin.saml.label_idp_metadata_url')}
              rules={[{ max: 1024 }]}
            >
              <Input className="mono" maxLength={1024} />
            </Form.Item>
            <Form.Item
              name="idp_entity_id"
              label={t('admin.saml.label_idp_entity_id')}
              rules={[{ max: 1024 }]}
            >
              <Input className="mono" maxLength={1024} />
            </Form.Item>
            <Form.Item name="signing_cert_pem" label={t('admin.saml.label_signing_cert')}>
              <Input.TextArea rows={5} className="mono" autoComplete="off" />
            </Form.Item>
          </Section>

          <Section title={t('admin.saml.section_sp')}>
            <Grid>
              <Form.Item
                name="sp_entity_id"
                label={t('admin.saml.label_sp_entity_id')}
                rules={[{ max: 1024 }]}
              >
                <Input className="mono" maxLength={1024} />
              </Form.Item>
              <Form.Item
                name="acs_url"
                label={t('admin.saml.label_acs_url')}
                rules={[{ max: 1024 }]}
              >
                <Input className="mono" maxLength={1024} />
              </Form.Item>
              <Form.Item
                name="slo_url"
                label={t('admin.saml.label_slo_url')}
                rules={[{ max: 1024 }]}
              >
                <Input className="mono" maxLength={1024} />
              </Form.Item>
            </Grid>
          </Section>

          <Section title={t('admin.saml.section_attributes')}>
            <Grid>
              <Form.Item
                name="attr_email"
                label={t('admin.saml.label_attr_email')}
                rules={[{ required: true, max: 255 }]}
              >
                <Input className="mono" />
              </Form.Item>
              <Form.Item
                name="attr_display_name"
                label={t('admin.saml.label_attr_display_name')}
                rules={[{ required: true, max: 255 }]}
              >
                <Input className="mono" />
              </Form.Item>
              <Form.Item
                name="attr_role"
                label={t('admin.saml.label_attr_role')}
                rules={[{ max: 255 }]}
              >
                <Input className="mono" />
              </Form.Item>
              <Form.Item
                name="default_role"
                label={t('admin.saml.label_default_role')}
                rules={[{ required: true }]}
              >
                <Select
                  options={[
                    { value: 'ADMIN', label: 'ADMIN' },
                    { value: 'REVIEWER', label: 'REVIEWER' },
                    { value: 'ANALYST', label: 'ANALYST' },
                    { value: 'READONLY', label: 'READONLY' },
                  ]}
                />
              </Form.Item>
              <Form.Item
                name="active"
                label={t('admin.saml.label_active')}
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
            </Grid>
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
              {t('admin.saml.save_button')}
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

export default SamlConfigPage;
