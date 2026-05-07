import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Switch,
} from 'antd';
import {
  EditOutlined,
  MailOutlined,
  MessageOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  createChannel,
  listChannels,
  notificationChannelKeys,
  testChannel,
  updateChannel,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { fmtDate } from '@/utils/dateFormat';
import type {
  ChannelType,
  CreateNotificationChannelInput,
  NotificationChannel,
  UpdateNotificationChannelInput,
} from '@/types/api';

const MASK = '********';

interface ChannelFormValues {
  name: string;
  channel_type: ChannelType;
  active: boolean;
  smtp_host?: string;
  smtp_port?: number;
  smtp_user?: string;
  smtp_password?: string;
  smtp_tls?: boolean;
  from_address?: string;
  from_name?: string;
  webhook_url?: string;
  slack_channel?: string;
  url?: string;
  secret?: string;
  timeout_seconds?: number;
}

export function NotificationsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<NotificationChannel | null>(null);
  const [creating, setCreating] = useState(false);
  const [testEmailOpenFor, setTestEmailOpenFor] = useState<NotificationChannel | null>(null);
  const [testEmail, setTestEmail] = useState('');

  const channelsQuery = useQuery({
    queryKey: notificationChannelKeys.lists(),
    queryFn: listChannels,
  });

  const createMutation = useMutation({
    mutationFn: (input: CreateNotificationChannelInput) => createChannel(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationChannelKeys.all });
      message.success(t('admin.notifications.create_success'));
      setCreating(false);
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; payload: UpdateNotificationChannelInput }) =>
      updateChannel(vars.id, vars.payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationChannelKeys.all });
      message.success(t('admin.notifications.update_success'));
      setEditing(null);
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const testMutation = useMutation({
    mutationFn: (vars: { id: string; email?: string }) =>
      testChannel(vars.id, vars.email ? { email: vars.email } : {}),
    onSuccess: () => {
      message.success(t('admin.notifications.test_success'));
      setTestEmailOpenFor(null);
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const channels = channelsQuery.data ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.notifications.title')}
        subtitle={t('admin.notifications.subtitle')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => channelsQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('admin.notifications.add_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {channelsQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : channelsQuery.isError ? (
          <EmptyState
            title={t('admin.notifications.load_error')}
            description={adminErrorMessage(channelsQuery.error)}
          />
        ) : channels.length === 0 ? (
          <EmptyState
            title={t('admin.notifications.title')}
            description={t('admin.notifications.empty')}
          />
        ) : (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))',
              gap: 14,
              alignContent: 'start',
            }}
          >
            {channels.map((c) => (
              <ChannelCard
                key={c.id}
                ch={c}
                onEdit={() => setEditing(c)}
                onTest={() => {
                  if (c.channel_type === 'EMAIL') {
                    setTestEmail('');
                    setTestEmailOpenFor(c);
                  } else {
                    testMutation.mutate({ id: c.id });
                  }
                }}
                testing={testMutation.isPending}
              />
            ))}
          </div>
        )}
      </div>

      <ChannelFormModal
        open={creating || !!editing}
        editing={editing}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSubmit={(payload) => {
          if (editing) {
            updateMutation.mutate({ id: editing.id, payload });
          } else {
            const create = payload as CreateNotificationChannelInput;
            createMutation.mutate(create);
          }
        }}
        loading={createMutation.isPending || updateMutation.isPending}
      />

      <Modal
        open={!!testEmailOpenFor}
        title={t('common.test')}
        onCancel={() => setTestEmailOpenFor(null)}
        onOk={() =>
          testEmailOpenFor &&
          testMutation.mutate({ id: testEmailOpenFor.id, email: testEmail.trim() || undefined })
        }
        okText={t('common.test')}
        cancelText={t('common.cancel')}
        confirmLoading={testMutation.isPending}
        destroyOnHidden
      >
        <Form layout="vertical">
          <Form.Item label={t('admin.notifications.test_email_label')}>
            <Input
              type="email"
              value={testEmail}
              onChange={(e) => setTestEmail(e.target.value)}
              placeholder="ops@example.com"
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function ChannelCard({
  ch,
  onEdit,
  onTest,
  testing,
}: {
  ch: NotificationChannel;
  onEdit: () => void;
  onTest: () => void;
  testing: boolean;
}) {
  const { t } = useTranslation();
  const icon =
    ch.channel_type === 'EMAIL' ? (
      <MailOutlined style={{ fontSize: 18 }} />
    ) : ch.channel_type === 'SLACK' ? (
      <MessageOutlined style={{ fontSize: 18 }} />
    ) : (
      <SendOutlined style={{ fontSize: 18 }} />
    );
  const iconColor =
    ch.channel_type === 'EMAIL' ? '#2563eb' : ch.channel_type === 'SLACK' ? '#7c3aed' : '#ea580c';
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 8,
            background: iconColor + '20',
            color: iconColor,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          {icon}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ fontWeight: 600, fontSize: 14 }}>{ch.name}</div>
            {!ch.active && (
              <Pill
                fg="var(--fg-muted)"
                bg="var(--status-neutral-bg)"
                border="var(--status-neutral-border)"
                size="sm"
              >
                disabled
              </Pill>
            )}
          </div>
          <div className="mono muted" style={{ fontSize: 11, marginTop: 2 }}>
            {ch.channel_type}
          </div>
        </div>
      </div>
      <div
        style={{
          marginTop: 12,
          padding: 10,
          background: 'var(--bg-sunken)',
          borderRadius: 6,
          fontSize: 11.5,
          fontFamily: 'var(--font-mono)',
          lineHeight: 1.5,
          maxHeight: 110,
          overflow: 'auto',
        }}
      >
        <ConfigPreview channel={ch} />
      </div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginTop: 12,
          paddingTop: 12,
          borderTop: '1px solid var(--border)',
        }}
      >
        <span className="mono muted" style={{ fontSize: 10 }}>
          {fmtDate(ch.created_at).split(',')[0]}
        </span>
        <div style={{ flex: 1 }} />
        <Button size="small" icon={<PlayCircleOutlined />} onClick={onTest} loading={testing}>
          {t('common.test')}
        </Button>
        <Button size="small" icon={<EditOutlined />} onClick={onEdit}>
          {t('common.edit')}
        </Button>
      </div>
    </div>
  );
}

function ConfigPreview({ channel }: { channel: NotificationChannel }) {
  const cfg = channel.config;
  if (channel.channel_type === 'EMAIL') {
    return (
      <>
        host: {String(cfg.smtp_host ?? '—')}:{String(cfg.smtp_port ?? '')}
        <br />
        from: {String(cfg.from_address ?? '—')}
      </>
    );
  }
  if (channel.channel_type === 'SLACK') {
    const url = String(cfg.webhook_url ?? '');
    return (
      <>
        channel: {String(cfg.channel ?? '—')}
        <br />
        webhook: {url ? url.slice(0, 38) + (url.length > 38 ? '…' : '') : '—'}
      </>
    );
  }
  const url = String(cfg.url ?? '');
  return (
    <>
      url: {url}
      <br />
      timeout: {String(cfg.timeout_seconds ?? '10')}s
    </>
  );
}

function ChannelFormModal({
  open,
  editing,
  onClose,
  onSubmit,
  loading,
}: {
  open: boolean;
  editing: NotificationChannel | null;
  onClose: () => void;
  onSubmit: (payload: CreateNotificationChannelInput | UpdateNotificationChannelInput) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<ChannelFormValues>();
  const channelType = Form.useWatch('channel_type', form);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      const c = editing.config;
      form.setFieldsValue({
        name: editing.name,
        channel_type: editing.channel_type,
        active: editing.active,
        smtp_host: c.smtp_host as string | undefined,
        smtp_port: c.smtp_port as number | undefined,
        smtp_user: c.smtp_user as string | undefined,
        smtp_password: c.smtp_password as string | undefined,
        smtp_tls: c.smtp_tls as boolean | undefined,
        from_address: c.from_address as string | undefined,
        from_name: c.from_name as string | undefined,
        webhook_url: c.webhook_url as string | undefined,
        slack_channel: c.channel as string | undefined,
        url: c.url as string | undefined,
        secret: c.secret as string | undefined,
        timeout_seconds: c.timeout_seconds as number | undefined,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ channel_type: 'EMAIL', active: true, smtp_tls: true });
    }
  }, [open, editing, form]);

  const onFinish = (values: ChannelFormValues) => {
    const config: Record<string, unknown> = {};
    const setIf = (key: string, value: unknown) => {
      if (value !== undefined && value !== null && value !== '') config[key] = value;
    };
    if (values.channel_type === 'EMAIL') {
      setIf('smtp_host', values.smtp_host);
      setIf('smtp_port', values.smtp_port);
      setIf('smtp_user', values.smtp_user);
      // Pass through MASK to preserve, otherwise send fresh value.
      setIf('smtp_password', values.smtp_password);
      setIf('smtp_tls', values.smtp_tls);
      setIf('from_address', values.from_address);
      setIf('from_name', values.from_name);
    } else if (values.channel_type === 'SLACK') {
      setIf('webhook_url', values.webhook_url);
      setIf('channel', values.slack_channel);
    } else {
      setIf('url', values.url);
      setIf('secret', values.secret);
      setIf('timeout_seconds', values.timeout_seconds);
    }
    if (editing) {
      onSubmit({
        name: values.name.trim(),
        active: values.active,
        config,
      });
    } else {
      onSubmit({
        name: values.name.trim(),
        channel_type: values.channel_type,
        config,
      });
    }
  };

  return (
    <Modal
      open={open}
      title={
        editing
          ? t('admin.notifications.modal_edit_title', { name: editing.name })
          : t('admin.notifications.modal_create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={
        editing ? t('admin.notifications.save_update') : t('admin.notifications.save_create')
      }
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
      width={640}
    >
      <Form<ChannelFormValues> form={form} layout="vertical" onFinish={onFinish}>
        <Form.Item
          name="name"
          label={t('admin.notifications.label_name')}
          rules={[{ required: true, max: 255, whitespace: true }]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item
          name="channel_type"
          label={t('admin.notifications.label_type')}
          rules={[{ required: true }]}
        >
          <Select
            disabled={!!editing}
            options={[
              { value: 'EMAIL', label: 'EMAIL' },
              { value: 'SLACK', label: 'SLACK' },
              { value: 'WEBHOOK', label: 'WEBHOOK' },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="active"
          label={t('admin.notifications.label_active')}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>

        {channelType === 'EMAIL' && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item
              name="smtp_host"
              label={t('admin.notifications.label_smtp_host')}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="smtp_port"
              label={t('admin.notifications.label_smtp_port')}
              rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}
            >
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="smtp_user" label={t('admin.notifications.label_smtp_user')}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item
              name="smtp_password"
              label={t('admin.notifications.label_smtp_password')}
              rules={[{ required: !editing }]}
            >
              <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="smtp_tls"
              label={t('admin.notifications.label_smtp_tls')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="from_address"
              label={t('admin.notifications.label_from_address')}
              rules={[{ required: true, type: 'email' }]}
            >
              <Input />
            </Form.Item>
            <Form.Item name="from_name" label={t('admin.notifications.label_from_name')}>
              <Input />
            </Form.Item>
          </div>
        )}

        {channelType === 'SLACK' && (
          <>
            <Form.Item
              name="webhook_url"
              label={t('admin.notifications.label_webhook_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input />
            </Form.Item>
            <Form.Item name="slack_channel" label={t('admin.notifications.label_slack_channel')}>
              <Input placeholder="#data-access" />
            </Form.Item>
          </>
        )}

        {channelType === 'WEBHOOK' && (
          <>
            <Form.Item
              name="url"
              label={t('admin.notifications.label_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="secret"
              label={t('admin.notifications.label_secret')}
              rules={[{ required: !editing }]}
            >
              <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="timeout_seconds"
              label={t('admin.notifications.label_timeout_seconds')}
              rules={[{ type: 'number', min: 1, max: 120 }]}
            >
              <InputNumber min={1} max={120} style={{ width: '100%' }} />
            </Form.Item>
          </>
        )}
      </Form>
    </Modal>
  );
}
