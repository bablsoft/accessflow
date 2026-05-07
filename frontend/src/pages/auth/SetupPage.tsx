import { useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { ArrowRightOutlined, LoadingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { submitSetup, type SetupRequest } from '@/api/setup';
import { useSetupStore } from '@/store/setupStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { setupErrorMessage } from '@/utils/apiErrors';

interface SetupFormValues {
  organization_name: string;
  email: string;
  display_name?: string;
  password: string;
  confirm_password: string;
}

export function SetupPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const setSetupRequired = useSetupStore((s) => s.setSetupRequired);
  const edition = usePreferencesStore((s) => s.edition);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form] = Form.useForm<SetupFormValues>();

  const onFinish = async (values: SetupFormValues): Promise<void> => {
    setError(null);
    setSubmitting(true);
    try {
      const req: SetupRequest = {
        organization_name: values.organization_name.trim(),
        email: values.email.trim(),
        password: values.password,
      };
      const displayName = values.display_name?.trim();
      if (displayName) req.display_name = displayName;
      await submitSetup(req);
      setSetupRequired(false);
      navigate('/login', { state: { setupSuccess: true } });
    } catch (err) {
      setError(setupErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--bg)',
        padding: 24,
      }}
    >
      <div style={{ width: 440 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            marginBottom: 28,
            justifyContent: 'center',
          }}
        >
          <div
            style={{
              width: 32,
              height: 32,
              borderRadius: 8,
              background: 'var(--fg)',
              color: 'var(--fg-inverse)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontFamily: 'var(--font-mono)',
              fontWeight: 700,
              fontSize: 13,
            }}
          >
            AF
          </div>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.01em' }}>
              {t('common.app_name')}
            </div>
            <div className="mono muted" style={{ fontSize: 10 }}>
              {edition.toLowerCase()} · v0.1.0
            </div>
          </div>
        </div>

        <div
          className="af-card"
          style={{
            padding: 24,
            background: 'var(--bg-elev)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
          }}
        >
          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>
              {t('auth.setup.title')}
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              {t('auth.setup.subtitle')}
            </div>
          </div>

          {error && (
            <Alert
              type="error"
              message={error}
              style={{ marginBottom: 16 }}
              showIcon
              closable
              onClose={() => setError(null)}
            />
          )}

          <Form<SetupFormValues>
            form={form}
            layout="vertical"
            onFinish={onFinish}
            requiredMark={false}
            disabled={submitting}
          >
            <Form.Item
              label={t('auth.setup.org_name_label')}
              name="organization_name"
              rules={[
                { required: true, message: t('validation.org_name_required') },
                { max: 255, message: t('validation.field_max_255') },
              ]}
            >
              <Input placeholder={t('auth.setup.org_name_placeholder')} autoComplete="organization" />
            </Form.Item>

            <Form.Item
              label={t('auth.setup.email_label')}
              name="email"
              rules={[
                { required: true, message: t('validation.email_required') },
                { type: 'email', message: t('validation.email_invalid') },
                { max: 255, message: t('validation.field_max_255') },
              ]}
            >
              <Input type="email" placeholder={t('auth.setup.email_placeholder')} autoComplete="email" />
            </Form.Item>

            <Form.Item
              label={t('auth.setup.display_name_label')}
              name="display_name"
              rules={[{ max: 255, message: t('validation.field_max_255') }]}
            >
              <Input placeholder={t('auth.setup.display_name_placeholder')} autoComplete="name" />
            </Form.Item>

            <Form.Item
              label={t('auth.setup.password_label')}
              name="password"
              rules={[
                { required: true, message: t('validation.password_required') },
                { min: 8, max: 128, message: t('validation.password_size') },
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>

            <Form.Item
              label={t('auth.setup.confirm_password_label')}
              name="confirm_password"
              dependencies={['password']}
              rules={[
                { required: true, message: t('validation.confirm_password_required') },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('password') === value) {
                      return Promise.resolve();
                    }
                    return Promise.reject(new Error(t('validation.passwords_mismatch')));
                  },
                }),
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>

            <Button
              type="primary"
              size="large"
              block
              htmlType="submit"
              disabled={submitting}
              icon={submitting ? <LoadingOutlined /> : <ArrowRightOutlined />}
            >
              {submitting ? t('auth.setup.submitting') : t('auth.setup.submit')}
            </Button>
          </Form>
        </div>

        <div
          className="muted mono"
          style={{
            fontSize: 11,
            textAlign: 'center',
            marginTop: 20,
          }}
        >
          {t('auth.setup.footer')}
        </div>
      </div>
    </div>
  );
}
