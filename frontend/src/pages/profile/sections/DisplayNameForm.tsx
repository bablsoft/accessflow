import { useEffect, useState } from 'react';
import { Alert, App, Button, Form, Input } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { meKeys, updateProfile } from '@/api/me';
import type { MeProfile } from '@/types/api';
import { profileErrorMessage } from '@/utils/apiErrors';
import { useAuthStore } from '@/store/authStore';

interface DisplayNameFormValues {
  display_name: string;
}

interface DisplayNameFormProps {
  profile: MeProfile;
}

export function DisplayNameForm({ profile }: DisplayNameFormProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<DisplayNameFormValues>();
  const [error, setError] = useState<string | null>(null);
  const updateAuthUser = useAuthStore((s) => s.user);
  const setSession = useAuthStore((s) => s.setSession);
  const accessToken = useAuthStore((s) => s.accessToken);

  useEffect(() => {
    form.setFieldsValue({ display_name: profile.display_name });
  }, [profile.display_name, form]);

  const mutation = useMutation({
    mutationFn: (values: DisplayNameFormValues) =>
      updateProfile({ display_name: values.display_name }),
    onSuccess: (updated) => {
      setError(null);
      queryClient.invalidateQueries({ queryKey: meKeys.current });
      // Keep authStore.user in sync so Topbar reflects the new name.
      if (updateAuthUser && accessToken) {
        setSession({
          access_token: accessToken,
          expires_in: 0,
          user: { ...updateAuthUser, display_name: updated.display_name },
        });
      }
      message.success(t('profile.display_name.saved'));
    },
    onError: (err) => setError(profileErrorMessage(err)),
  });

  return (
    <Form<DisplayNameFormValues>
      form={form}
      layout="vertical"
      initialValues={{ display_name: profile.display_name }}
      onFinish={(values) => mutation.mutate(values)}
      disabled={mutation.isPending}
    >
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
      <Form.Item
        name="display_name"
        label={t('profile.display_name.label')}
        rules={[
          { required: true, message: t('validation.display_name_required') },
          { max: 255, message: t('validation.field_max_255') },
        ]}
      >
        <Input placeholder={t('profile.display_name.placeholder')} autoComplete="name" />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending}>
        {t('profile.display_name.save')}
      </Button>
    </Form>
  );
}
