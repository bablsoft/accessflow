import { useState } from 'react';
import { Alert, App, Button, Form, Input, Modal } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { disableTotp, meKeys } from '@/api/me';
import { apiErrorTraceId, profileErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';

interface TotpDisableDialogProps {
  open: boolean;
  onClose: () => void;
}

interface DisableValues {
  current_password: string;
}

export function TotpDisableDialog({ open, onClose }: TotpDisableDialogProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<DisableValues>();
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);

  const mutation = useMutation({
    mutationFn: (values: DisableValues) =>
      disableTotp({ current_password: values.current_password }),
    onSuccess: () => {
      setError(null);
      message.success(t('profile.totp.disabled_success'));
      queryClient.invalidateQueries({ queryKey: meKeys.current });
      form.resetFields();
      onClose();
    },
    onError: (err) => setError({ message: profileErrorMessage(err), traceId: apiErrorTraceId(err) }),
  });

  return (
    <Modal
      open={open}
      onCancel={() => {
        if (!mutation.isPending) {
          form.resetFields();
          setError(null);
          onClose();
        }
      }}
      title={t('profile.totp.disable_title')}
      footer={null}
      destroyOnHidden
    >
      <Form<DisableValues>
        form={form}
        layout="vertical"
        onFinish={(values) => mutation.mutate(values)}
        disabled={mutation.isPending}
      >
        {error && (
          <Alert
            type="error"
            message={error.message}
            description={error.traceId ? <TraceIdFooter traceId={error.traceId} /> : undefined}
            style={{ marginBottom: 16 }}
            showIcon
            closable
            onClose={() => setError(null)}
          />
        )}
        <p>{t('profile.totp.disable_help')}</p>
        <Form.Item
          name="current_password"
          label={t('profile.password.current_label')}
          rules={[{ required: true, message: t('validation.current_password_required') }]}
        >
          <Input.Password autoComplete="current-password" autoFocus />
        </Form.Item>
        <Button type="primary" danger htmlType="submit" loading={mutation.isPending}>
          {t('profile.totp.disable_button')}
        </Button>
      </Form>
    </Modal>
  );
}
