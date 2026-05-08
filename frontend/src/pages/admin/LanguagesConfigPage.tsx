import { useEffect } from 'react';
import { App, Button, Checkbox, Form, Select, Skeleton } from 'antd';
import type { CheckboxChangeEvent } from 'antd/es/checkbox';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  getAdminLocalizationConfig,
  localizationKeys,
  updateAdminLocalizationConfig,
} from '@/api/localization';
import { adminErrorMessage } from '@/utils/apiErrors';
import { LANGUAGE_DISPLAY_NAMES, SUPPORTED_LANGUAGES, type Language } from '@/i18n';
import type { UpdateLocalizationConfigInput } from '@/types/api';

interface LanguagesFormValues {
  available_languages: Language[];
  default_language: Language;
  ai_review_language: Language;
}

export function LanguagesConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<LanguagesFormValues>();

  const cfgQuery = useQuery({
    queryKey: localizationKeys.admin(),
    queryFn: getAdminLocalizationConfig,
  });

  useEffect(() => {
    if (cfgQuery.data) {
      form.setFieldsValue({
        available_languages: cfgQuery.data.available_languages.filter(isLanguage),
        default_language: isLanguage(cfgQuery.data.default_language)
          ? cfgQuery.data.default_language
          : 'en',
        ai_review_language: isLanguage(cfgQuery.data.ai_review_language)
          ? cfgQuery.data.ai_review_language
          : 'en',
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateLocalizationConfigInput) => updateAdminLocalizationConfig(payload),
    onSuccess: (payload) => {
      queryClient.setQueryData(localizationKeys.admin(), payload);
      void queryClient.invalidateQueries({ queryKey: localizationKeys.me() });
      message.success(t('admin.languages.save_success'));
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const onSave = (values: LanguagesFormValues) => {
    saveMutation.mutate({
      available_languages: values.available_languages,
      default_language: values.default_language,
      ai_review_language: values.ai_review_language,
    });
  };

  if (cfgQuery.isLoading || !cfgQuery.data) {
    if (cfgQuery.isError) {
      return (
        <EmptyState
          title={t('admin.languages.load_error')}
          description={adminErrorMessage(cfgQuery.error)}
        />
      );
    }
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 6 }} />
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('admin.languages.title')} subtitle={t('admin.languages.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <Form<LanguagesFormValues>
          form={form}
          layout="vertical"
          onFinish={onSave}
          initialValues={{
            available_languages: ['en' as Language],
            default_language: 'en' as Language,
            ai_review_language: 'en' as Language,
          }}
        >
          <Section title={t('admin.languages.section_user_languages')}>
            <Form.Item
              extra={t('admin.languages.section_user_languages_help')}
              name="available_languages"
              rules={[
                {
                  required: true,
                  message: t('errors.languages_save_error'),
                  type: 'array',
                  min: 1,
                },
              ]}
            >
              <AvailableLanguagesField />
            </Form.Item>

            <Form.Item
              label={t('admin.languages.label_default_language')}
              name="default_language"
              dependencies={['available_languages']}
              rules={[{ required: true, message: t('errors.languages_save_error') }]}
            >
              <DefaultLanguageSelect />
            </Form.Item>
          </Section>

          <Section title={t('admin.languages.section_ai_language')}>
            <Form.Item
              extra={t('admin.languages.section_ai_language_help')}
              name="ai_review_language"
              rules={[{ required: true, message: t('errors.languages_save_error') }]}
            >
              <Select<Language>
                options={SUPPORTED_LANGUAGES.map((code) => ({
                  value: code,
                  label: `${LANGUAGE_DISPLAY_NAMES[code]} (${code})`,
                }))}
              />
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
            <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
              {t('admin.languages.save_button')}
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

interface AvailableLanguagesFieldProps {
  value?: Language[];
  onChange?: (next: Language[]) => void;
}

function AvailableLanguagesField({ value = [], onChange }: AvailableLanguagesFieldProps) {
  const handleToggle = (code: Language) => (event: CheckboxChangeEvent) => {
    const checked = event.target.checked;
    const next = checked
      ? Array.from(new Set([...value, code]))
      : value.filter((c) => c !== code);
    onChange?.(next);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {SUPPORTED_LANGUAGES.map((code) => (
        <Checkbox key={code} checked={value.includes(code)} onChange={handleToggle(code)}>
          {LANGUAGE_DISPLAY_NAMES[code]} ({code})
        </Checkbox>
      ))}
    </div>
  );
}

interface DefaultLanguageSelectProps {
  value?: Language;
  onChange?: (next: Language) => void;
}

function DefaultLanguageSelect({ value, onChange }: DefaultLanguageSelectProps) {
  const available = (Form.useWatch<Language[] | undefined>('available_languages') ?? []) as Language[];
  return (
    <Select<Language>
      value={value}
      onChange={onChange}
      options={(available.length ? available : SUPPORTED_LANGUAGES).map((code) => ({
        value: code,
        label: `${LANGUAGE_DISPLAY_NAMES[code]} (${code})`,
      }))}
    />
  );
}

function isLanguage(code: string): code is Language {
  return (SUPPORTED_LANGUAGES as readonly string[]).includes(code);
}
