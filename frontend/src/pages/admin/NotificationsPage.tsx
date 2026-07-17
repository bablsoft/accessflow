import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Skeleton,
  Switch,
} from 'antd';
import {
  AlertOutlined,
  ApiOutlined,
  BugOutlined,
  DeleteOutlined,
  EditOutlined,
  MailOutlined,
  MessageOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  SendOutlined,
  TeamOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { SystemSmtpCard } from '@/components/admin/SystemSmtpCard';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  createChannel,
  deleteChannel,
  listChannels,
  notificationChannelKeys,
  testChannel,
  updateChannel,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { channelTypeLabel, enumOptions } from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import type {
  ChannelType,
  CreateNotificationChannelInput,
  NotificationChannel,
  UpdateNotificationChannelInput,
} from '@/types/api';

const MASK = '********';
const CHANNEL_TYPES: readonly ChannelType[] = [
  'EMAIL',
  'SLACK',
  'WEBHOOK',
  'DISCORD',
  'TELEGRAM',
  'MS_TEAMS',
  'PAGERDUTY',
  'SERVICENOW',
  'JIRA',
] as const;

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
  discord_username?: string;
  discord_avatar_url?: string;
  bot_token?: string;
  chat_id?: string;
  routing_key?: string;
  default_severity?: string;
  triggers?: string[];
  instance_url?: string;
  sn_username?: string;
  password?: string;
  assignment_group?: string;
  urgency?: number;
  base_url?: string;
  user_email?: string;
  api_token?: string;
  project_key?: string;
  issue_type?: string;
  bidirectional_sync?: boolean;
  webhook_secret?: string;
  approve_statuses?: string[];
  reject_statuses?: string[];
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
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; payload: UpdateNotificationChannelInput }) =>
      updateChannel(vars.id, vars.payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationChannelKeys.all });
      message.success(t('admin.notifications.update_success'));
      setEditing(null);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: (vars: { id: string; email?: string }) =>
      testChannel(vars.id, vars.email ? { email: vars.email } : {}),
    onSuccess: () => {
      message.success(t('admin.notifications.test_success'));
      setTestEmailOpenFor(null);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteChannel(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationChannelKeys.all });
      message.success(t('admin.notifications.delete_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const channels = channelsQuery.data ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-notification-channels"
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
        <SystemSmtpCard />
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
                onDelete={() => deleteMutation.mutate(c.id)}
                testing={testMutation.isPending}
                deleting={deleteMutation.isPending}
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
  onDelete,
  testing,
  deleting,
}: {
  ch: NotificationChannel;
  onEdit: () => void;
  onTest: () => void;
  onDelete: () => void;
  testing: boolean;
  deleting: boolean;
}) {
  const { t } = useTranslation();
  const icon = (() => {
    switch (ch.channel_type) {
      case 'EMAIL':
        return <MailOutlined style={{ fontSize: 18 }} />;
      case 'SLACK':
        return <MessageOutlined style={{ fontSize: 18 }} />;
      case 'WEBHOOK':
        return <SendOutlined style={{ fontSize: 18 }} />;
      case 'DISCORD':
        return <RocketOutlined style={{ fontSize: 18 }} />;
      case 'TELEGRAM':
        return <ApiOutlined style={{ fontSize: 18 }} />;
      case 'MS_TEAMS':
        return <TeamOutlined style={{ fontSize: 18 }} />;
      case 'PAGERDUTY':
        return <AlertOutlined style={{ fontSize: 18 }} />;
      case 'SERVICENOW':
        return <ToolOutlined style={{ fontSize: 18 }} />;
      case 'JIRA':
        return <BugOutlined style={{ fontSize: 18 }} />;
      default:
        return <SendOutlined style={{ fontSize: 18 }} />;
    }
  })();
  const iconColor = (() => {
    switch (ch.channel_type) {
      case 'EMAIL':
        return '#2563eb';
      case 'SLACK':
        return '#7c3aed';
      case 'WEBHOOK':
        return '#ea580c';
      case 'DISCORD':
        return '#5865f2';
      case 'TELEGRAM':
        return '#229ed9';
      case 'MS_TEAMS':
        return '#4b53bc';
      case 'PAGERDUTY':
        return '#06ac38';
      case 'SERVICENOW':
        return '#0f8577';
      case 'JIRA':
        return '#0052cc';
      default:
        return '#64748b';
    }
  })();
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
            {channelTypeLabel(t, ch.channel_type)}
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
        <Popconfirm
          title={t('admin.notifications.delete_confirm_title')}
          description={t('admin.notifications.delete_confirm_body', { name: ch.name })}
          okText={t('common.delete')}
          okButtonProps={{ danger: true }}
          cancelText={t('common.cancel')}
          onConfirm={onDelete}
        >
          <Button
            size="small"
            icon={<DeleteOutlined />}
            danger
            loading={deleting}
            aria-label={t('admin.notifications.action_delete')}
          />
        </Popconfirm>
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
  if (channel.channel_type === 'DISCORD') {
    const url = String(cfg.webhook_url ?? '');
    return (
      <>
        username: {String(cfg.username ?? '—')}
        <br />
        webhook: {url ? url.slice(0, 38) + (url.length > 38 ? '…' : '') : '—'}
      </>
    );
  }
  if (channel.channel_type === 'TELEGRAM') {
    return (
      <>
        chat_id: {String(cfg.chat_id ?? '—')}
        <br />
        bot token: {String(cfg.bot_token ?? '—')}
      </>
    );
  }
  if (channel.channel_type === 'MS_TEAMS') {
    const url = String(cfg.webhook_url ?? '');
    return <>webhook: {url ? url.slice(0, 38) + (url.length > 38 ? '…' : '') : '—'}</>;
  }
  if (channel.channel_type === 'PAGERDUTY') {
    const triggers = Array.isArray(cfg.triggers) ? (cfg.triggers as string[]) : [];
    return (
      <>
        severity: {String(cfg.default_severity ?? '—')}
        <br />
        triggers: {triggers.length ? triggers.join(', ') : '—'}
      </>
    );
  }
  if (channel.channel_type === 'SERVICENOW') {
    const triggers = Array.isArray(cfg.triggers) ? (cfg.triggers as string[]) : [];
    return (
      <>
        instance: {String(cfg.instance_url ?? '—')}
        <br />
        triggers: {triggers.length ? triggers.join(', ') : '—'}
        <br />
        sync: {cfg.bidirectional_sync ? 'on' : 'off'}
      </>
    );
  }
  if (channel.channel_type === 'JIRA') {
    const triggers = Array.isArray(cfg.triggers) ? (cfg.triggers as string[]) : [];
    return (
      <>
        project: {String(cfg.project_key ?? '—')} @ {String(cfg.base_url ?? '—')}
        <br />
        triggers: {triggers.length ? triggers.join(', ') : '—'}
        <br />
        sync: {cfg.bidirectional_sync ? 'on' : 'off'}
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
  const bidirectionalSync = Form.useWatch('bidirectional_sync', form);

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
        discord_username: c.username as string | undefined,
        discord_avatar_url: c.avatar_url as string | undefined,
        bot_token: c.bot_token as string | undefined,
        chat_id: c.chat_id as string | undefined,
        routing_key: c.routing_key as string | undefined,
        default_severity: c.default_severity as string | undefined,
        triggers: c.triggers as string[] | undefined,
        instance_url: c.instance_url as string | undefined,
        sn_username:
          editing.channel_type === 'SERVICENOW' ? (c.username as string | undefined) : undefined,
        password: c.password as string | undefined,
        assignment_group: c.assignment_group as string | undefined,
        urgency: c.urgency as number | undefined,
        base_url: c.base_url as string | undefined,
        user_email: c.user_email as string | undefined,
        api_token: c.api_token as string | undefined,
        project_key: c.project_key as string | undefined,
        issue_type: c.issue_type as string | undefined,
        bidirectional_sync: c.bidirectional_sync as boolean | undefined,
        webhook_secret: c.webhook_secret as string | undefined,
        approve_statuses: c.approve_statuses as string[] | undefined,
        reject_statuses: c.reject_statuses as string[] | undefined,
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
    } else if (values.channel_type === 'DISCORD') {
      setIf('webhook_url', values.webhook_url);
      setIf('username', values.discord_username);
      setIf('avatar_url', values.discord_avatar_url);
    } else if (values.channel_type === 'TELEGRAM') {
      setIf('bot_token', values.bot_token);
      setIf('chat_id', values.chat_id);
    } else if (values.channel_type === 'MS_TEAMS') {
      setIf('webhook_url', values.webhook_url);
    } else if (values.channel_type === 'PAGERDUTY') {
      setIf('routing_key', values.routing_key);
      setIf('default_severity', values.default_severity);
      if (values.triggers && values.triggers.length) config['triggers'] = values.triggers;
    } else if (values.channel_type === 'SERVICENOW' || values.channel_type === 'JIRA') {
      if (values.channel_type === 'SERVICENOW') {
        setIf('instance_url', values.instance_url);
        setIf('username', values.sn_username);
        setIf('password', values.password);
        setIf('assignment_group', values.assignment_group);
        setIf('urgency', values.urgency);
      } else {
        setIf('base_url', values.base_url);
        setIf('user_email', values.user_email);
        setIf('api_token', values.api_token);
        setIf('project_key', values.project_key);
        setIf('issue_type', values.issue_type);
      }
      if (values.triggers && values.triggers.length) config['triggers'] = values.triggers;
      config['bidirectional_sync'] = !!values.bidirectional_sync;
      if (values.bidirectional_sync) setIf('webhook_secret', values.webhook_secret);
      if (values.approve_statuses && values.approve_statuses.length)
        config['approve_statuses'] = values.approve_statuses;
      if (values.reject_statuses && values.reject_statuses.length)
        config['reject_statuses'] = values.reject_statuses;
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
            options={enumOptions(CHANNEL_TYPES, channelTypeLabel, t)}
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

        {channelType === 'DISCORD' && (
          <>
            <Form.Item
              name="webhook_url"
              label={t('admin.notifications.label_discord_webhook_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input placeholder="https://discord.com/api/webhooks/…" />
            </Form.Item>
            <Form.Item
              name="discord_username"
              label={t('admin.notifications.label_discord_username')}
            >
              <Input placeholder="AccessFlow" />
            </Form.Item>
            <Form.Item
              name="discord_avatar_url"
              label={t('admin.notifications.label_discord_avatar_url')}
              rules={[{ type: 'url' }]}
            >
              <Input placeholder="https://example.com/avatar.png" />
            </Form.Item>
          </>
        )}

        {channelType === 'TELEGRAM' && (
          <>
            <Form.Item
              name="bot_token"
              label={t('admin.notifications.label_telegram_bot_token')}
              rules={[{ required: !editing }]}
            >
              <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="chat_id"
              label={t('admin.notifications.label_telegram_chat_id')}
              rules={[{ required: true }]}
            >
              <Input placeholder="-1001234567890" />
            </Form.Item>
          </>
        )}

        {channelType === 'MS_TEAMS' && (
          <Form.Item
            name="webhook_url"
            label={t('admin.notifications.label_ms_teams_webhook_url')}
            rules={[{ required: true, type: 'url' }]}
          >
            <Input placeholder="https://example.webhook.office.com/webhookb2/…" />
          </Form.Item>
        )}

        {channelType === 'PAGERDUTY' && (
          <>
            <Form.Item
              name="routing_key"
              label={t('admin.notifications.label_pagerduty_routing_key')}
              rules={[{ required: !editing }]}
            >
              <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="default_severity"
              label={t('admin.notifications.label_pagerduty_severity')}
              rules={[{ required: true }]}
            >
              <Select
                options={[
                  { value: 'critical', label: t('admin.notifications.label_pagerduty_severity_critical') },
                  { value: 'error', label: t('admin.notifications.label_pagerduty_severity_error') },
                  { value: 'warning', label: t('admin.notifications.label_pagerduty_severity_warning') },
                  { value: 'info', label: t('admin.notifications.label_pagerduty_severity_info') },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="triggers"
              label={t('admin.notifications.label_pagerduty_triggers')}
              rules={[{ required: true, type: 'array', min: 1 }]}
            >
              <Checkbox.Group
                options={[
                  {
                    value: 'CRITICAL_RISK',
                    label: t('admin.notifications.label_pagerduty_trigger_critical_risk'),
                  },
                  {
                    value: 'REVIEW_TIMEOUT',
                    label: t('admin.notifications.label_pagerduty_trigger_review_timeout'),
                  },
                  {
                    value: 'ESCALATION',
                    label: t('admin.notifications.label_pagerduty_trigger_escalation'),
                  },
                ]}
              />
            </Form.Item>
          </>
        )}

        {channelType === 'SERVICENOW' && (
          <>
            <Form.Item
              name="instance_url"
              label={t('admin.notifications.label_servicenow_instance_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input placeholder="https://company.service-now.com" />
            </Form.Item>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Form.Item
                name="sn_username"
                label={t('admin.notifications.label_servicenow_username')}
                rules={[{ required: true }]}
              >
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item
                name="password"
                label={t('admin.notifications.label_servicenow_password')}
                rules={[{ required: !editing }]}
              >
                <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
              </Form.Item>
              <Form.Item
                name="assignment_group"
                label={t('admin.notifications.label_servicenow_assignment_group')}
              >
                <Input placeholder="Database Operations" />
              </Form.Item>
              <Form.Item
                name="urgency"
                label={t('admin.notifications.label_servicenow_urgency')}
                rules={[{ type: 'number', min: 1, max: 3 }]}
              >
                <InputNumber min={1} max={3} style={{ width: '100%' }} />
              </Form.Item>
            </div>
          </>
        )}

        {channelType === 'JIRA' && (
          <>
            <Form.Item
              name="base_url"
              label={t('admin.notifications.label_jira_base_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input placeholder="https://company.atlassian.net" />
            </Form.Item>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Form.Item
                name="user_email"
                label={t('admin.notifications.label_jira_user_email')}
                rules={[{ required: true, type: 'email' }]}
              >
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item
                name="api_token"
                label={t('admin.notifications.label_jira_api_token')}
                rules={[{ required: !editing }]}
              >
                <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
              </Form.Item>
              <Form.Item
                name="project_key"
                label={t('admin.notifications.label_jira_project_key')}
                rules={[{ required: true }]}
              >
                <Input placeholder="SEC" />
              </Form.Item>
              <Form.Item name="issue_type" label={t('admin.notifications.label_jira_issue_type')}>
                <Input placeholder="Task" />
              </Form.Item>
            </div>
          </>
        )}

        {(channelType === 'SERVICENOW' || channelType === 'JIRA') && (
          <>
            <Form.Item
              name="triggers"
              label={t('admin.notifications.label_ticketing_triggers')}
              rules={[{ required: true, type: 'array', min: 1 }]}
            >
              <Checkbox.Group
                options={[
                  {
                    value: 'QUERY_REJECTED',
                    label: t('admin.notifications.label_ticketing_trigger_query_rejected'),
                  },
                  {
                    value: 'REVIEW_TIMEOUT',
                    label: t('admin.notifications.label_ticketing_trigger_review_timeout'),
                  },
                  {
                    value: 'QUERY_ESCALATED',
                    label: t('admin.notifications.label_ticketing_trigger_query_escalated'),
                  },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="bidirectional_sync"
              label={t('admin.notifications.label_ticketing_bidirectional_sync')}
              valuePropName="checked"
              extra={t('admin.notifications.help_ticketing_bidirectional_sync')}
            >
              <Switch />
            </Form.Item>
            {bidirectionalSync && (
              <>
                <Form.Item
                  name="webhook_secret"
                  label={t('admin.notifications.label_ticketing_webhook_secret')}
                  rules={[{ required: !editing }]}
                >
                  <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
                </Form.Item>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <Form.Item
                    name="approve_statuses"
                    label={t('admin.notifications.label_ticketing_approve_statuses')}
                  >
                    <Select mode="tags" open={false} suffixIcon={null} placeholder="resolved, done…" />
                  </Form.Item>
                  <Form.Item
                    name="reject_statuses"
                    label={t('admin.notifications.label_ticketing_reject_statuses')}
                  >
                    <Select mode="tags" open={false} suffixIcon={null} placeholder="rejected, declined…" />
                  </Form.Item>
                </div>
              </>
            )}
          </>
        )}
      </Form>
    </Modal>
  );
}
