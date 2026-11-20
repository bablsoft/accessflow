import { useEffect, useState } from 'react';
import { App, Button, Form, Input, Modal, Skeleton, Space, Switch } from 'antd';
import { DeleteOutlined, EditOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { StatusTag } from '@/components/layout';
import {
  deleteSystemSmtp,
  getSystemSmtp,
  systemSmtpKeys,
  testSystemSmtp,
  updateSystemSmtp,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { UpdateSystemSmtpInput } from '@/types/api';

const MASK = '********';

interface FormValues {
  host: string;
  port: number;
  username?: string;
  smtp_password?: string;
  tls: boolean;
  from_address: string;
  from_name?: string;
}

export function SystemSmtpCard() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [testTo, setTestTo] = useState<string>('');
  const [testing, setTesting] = useState(false);
  const [form] = Form.useForm<FormValues>();

  const query = useQuery({
    queryKey: systemSmtpKeys.current(),
    queryFn: () => getSystemSmtp(),
  });

  const config = query.data ?? null;

  useEffect(() => {
    if (editing) {
      if (config) {
        form.setFieldsValue({
          host: config.host,
          port: config.port,
          username: config.username ?? undefined,
          smtp_password: config.smtp_password === MASK ? MASK : undefined,
          tls: config.tls,
          from_address: config.from_address,
          from_name: config.from_name ?? undefined,
        });
      } else {
        form.resetFields();
        form.setFieldsValue({ tls: true, port: 587 });
      }
    }
  }, [editing, config, form]);

  const saveMutation = useMutation({
    mutationFn: (input: UpdateSystemSmtpInput) => updateSystemSmtp(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: systemSmtpKeys.all });
      message.success(t('admin.notifications.system_smtp.save_success'));
      setEditing(false);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteSystemSmtp(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: systemSmtpKeys.all });
      message.success(t('admin.notifications.system_smtp.delete_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const sendTest = async (): Promise<void> => {
    setTesting(true);
    try {
      await testSystemSmtp(testTo ? { to: testTo } : {});
      message.success(t('admin.notifications.system_smtp.test_success'));
    } catch (err) {
      showApiError(message, err, adminErrorMessage);
    } finally {
      setTesting(false);
    }
  };

  const onFinish = (values: FormValues): void => {
    const payload: UpdateSystemSmtpInput = {
      host: values.host.trim(),
      port: Number(values.port),
      tls: values.tls,
      from_address: values.from_address.trim(),
    };
    const username = values.username?.trim();
    if (username) payload.username = username;
    const password = values.smtp_password?.trim();
    if (password && password !== MASK) payload.smtp_password = password;
    const fromName = values.from_name?.trim();
    if (fromName) payload.from_name = fromName;
    saveMutation.mutate(payload);
  };

  const confirmDelete = (): void => {
    void modal.confirm({
      title: t('admin.notifications.system_smtp.delete_title'),
      content: t('admin.notifications.system_smtp.delete_confirm'),
      okText: t('admin.notifications.system_smtp.delete_action'),
      okType: 'danger',
      onOk: () => deleteMutation.mutateAsync(),
    });
  };

  return (
    <div
      className="af-box af-card u-p-4 u-mb-6"
    >
      <div
        className="u-flex u-items-center u-justify-between u-mb-3"
      >
        <div>
          <div className="u-text-md u-fw-600">
            {t('admin.notifications.system_smtp.title')}
          </div>
          <div className="muted u-text-xs">
            {t('admin.notifications.system_smtp.subtitle')}
          </div>
        </div>
        <Space>
          {config && (
            <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(true)}>
              {t('admin.notifications.system_smtp.edit')}
            </Button>
          )}
          {!config && (
            <Button size="small" type="primary" onClick={() => setEditing(true)}>
              {t('admin.notifications.system_smtp.configure')}
            </Button>
          )}
          {config && (
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={confirmDelete}
              loading={deleteMutation.isPending}
            >
              {t('admin.notifications.system_smtp.delete_action')}
            </Button>
          )}
        </Space>
      </div>

      {query.isLoading ? (
        <Skeleton active paragraph={{ rows: 2 }} />
      ) : config ? (
        <>
          <div
            className="u-grid u-text-sm u-gap-2" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))' }}
          >
            <div>
              <div className="muted u-text-2xs">
                {t('admin.notifications.system_smtp.host_label')}
              </div>
              <div>
                {config.host}:{config.port}
              </div>
            </div>
            <div>
              <div className="muted u-text-2xs">
                {t('admin.notifications.system_smtp.from_label')}
              </div>
              <div>
                {config.from_name ? `${config.from_name} <${config.from_address}>` : config.from_address}
              </div>
            </div>
            <div>
              <div className="muted u-text-2xs">
                {t('admin.notifications.system_smtp.tls_label')}
              </div>
              <div>
                <StatusTag tone={config.tls ? 'success' : 'neutral'}>
                  {config.tls ? 'STARTTLS' : t('admin.notifications.system_smtp.tls_off')}
                </StatusTag>
              </div>
            </div>
          </div>
          <div className="u-flex u-gap-2 u-items-center u-mt-3">
            <Input
              size="small"
              style={{ maxWidth: 280 }}
              placeholder={t('admin.notifications.system_smtp.test_to_placeholder')}
              value={testTo}
              onChange={(e) => setTestTo(e.target.value)}
            />
            <Button
              size="small"
              icon={<SendOutlined />}
              onClick={() => void sendTest()}
              loading={testing}
            >
              {t('admin.notifications.system_smtp.test')}
            </Button>
          </div>
        </>
      ) : (
        <div className="muted u-text-sm">
          {t('admin.notifications.system_smtp.not_configured')}
        </div>
      )}

      <Modal
        title={t('admin.notifications.system_smtp.modal_title')}
        open={editing}
        onCancel={() => setEditing(false)}
        onOk={() => form.submit()}
        okText={t('admin.notifications.system_smtp.save')}
        confirmLoading={saveMutation.isPending}
        destroyOnHidden
      >
        <Form<FormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          requiredMark={false}
          initialValues={{ tls: true, port: 587 }}
        >
          <Form.Item
            label={t('admin.notifications.system_smtp.host_label')}
            name="host"
            rules={[
              { required: true, message: t('validation.system_smtp.host_required') },
              { max: 255, message: t('validation.field_max_255') },
            ]}
          >
            <Input placeholder="smtp.example.com" />
          </Form.Item>
          <Form.Item
            label={t('admin.notifications.system_smtp.port_label')}
            name="port"
            rules={[
              { required: true, message: t('validation.system_smtp.port_range') },
            ]}
          >
            <Input type="number" />
          </Form.Item>
          <Form.Item
            label={t('admin.notifications.system_smtp.username_label')}
            name="username"
            rules={[{ max: 255, message: t('validation.field_max_255') }]}
          >
            <Input autoComplete="username" />
          </Form.Item>
          <Form.Item
            label={t('admin.notifications.system_smtp.password_label')}
            name="smtp_password"
            extra={t('admin.notifications.system_smtp.password_help')}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            label={t('admin.notifications.system_smtp.tls_label')}
            name="tls"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            label={t('admin.notifications.system_smtp.from_address_label')}
            name="from_address"
            rules={[
              { required: true, message: t('validation.system_smtp.from_address_required') },
              { type: 'email', message: t('validation.system_smtp.from_address_invalid') },
              { max: 255, message: t('validation.field_max_255') },
            ]}
          >
            <Input type="email" placeholder="no-reply@example.com" />
          </Form.Item>
          <Form.Item
            label={t('admin.notifications.system_smtp.from_name_label')}
            name="from_name"
            rules={[{ max: 255, message: t('validation.field_max_255') }]}
          >
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
