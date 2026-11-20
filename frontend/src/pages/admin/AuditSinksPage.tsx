import { useEffect, useState, type ReactNode } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Skeleton,
  Switch,
  Tooltip,
} from 'antd';
import {
  CloudServerOutlined,
  CloudUploadOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  FileProtectOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Page, PageBody } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  auditSinkKeys,
  createAuditSink,
  deleteAuditSink,
  listAuditSinks,
  testAuditSink,
  updateAuditSink,
} from '@/api/auditSinks';
import { auditSinkErrorMessage } from '@/utils/apiErrors';
import { auditSinkTypeLabel, AUDIT_SINK_TYPES, enumOptions } from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import type {
  AuditSink,
  AuditSinkType,
  CreateAuditSinkInput,
  UpdateAuditSinkInput,
} from '@/types/api';

const MASK = '********';
// Delivery health is a live signal — keep the cards fresh like DatasourceHealthPage.
const REFRESH_MS = 30_000;

interface SinkFormValues {
  name: string;
  type: AuditSinkType;
  enabled?: boolean;
  url?: string;
  token?: string;
  index?: string;
  source?: string;
  host?: string;
  port?: number;
  protocol?: 'TCP' | 'TLS';
  secret?: string;
  bucket?: string;
  region?: string;
  access_key_id?: string;
  secret_access_key?: string;
  retention_days?: number;
  prefix?: string;
  endpoint?: string;
  retention_mode?: 'COMPLIANCE' | 'GOVERNANCE';
  segment_max_age?: string;
}

export default function AuditSinksPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<AuditSink | null>(null);
  const [creating, setCreating] = useState(false);

  const sinksQuery = useQuery({
    queryKey: auditSinkKeys.lists(),
    queryFn: listAuditSinks,
    // Per-sink delivery health (behind count, last error) goes stale fast.
    refetchInterval: REFRESH_MS,
  });

  const createMutation = useMutation({
    mutationFn: (input: CreateAuditSinkInput) => createAuditSink(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: auditSinkKeys.all });
      message.success(t('admin.audit_sinks.create_success'));
      setCreating(false);
    },
    onError: (err) => showApiError(message, err, auditSinkErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; payload: UpdateAuditSinkInput }) =>
      updateAuditSink(vars.id, vars.payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: auditSinkKeys.all });
      message.success(t('admin.audit_sinks.update_success'));
      setEditing(null);
    },
    onError: (err) => showApiError(message, err, auditSinkErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: (id: string) => testAuditSink(id),
    onSuccess: () => {
      message.success(t('admin.audit_sinks.test_success'));
    },
    onError: (err) => showApiError(message, err, auditSinkErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteAuditSink(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: auditSinkKeys.all });
      message.success(t('admin.audit_sinks.delete_success'));
    },
    onError: (err) => showApiError(message, err, auditSinkErrorMessage),
  });

  const sinks = sinksQuery.data ?? [];

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-audit-sinks"
        title={t('admin.audit_sinks.title')}
        subtitle={t('admin.audit_sinks.subtitle')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => sinksQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('admin.audit_sinks.add_button')}
            </Button>
          </>
        }
      />
      <PageBody>
        {sinksQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : sinksQuery.isError ? (
          <EmptyState
            icon={<CloudUploadOutlined className="u-text-xl" />}
            title={t('admin.audit_sinks.load_error')}
            description={auditSinkErrorMessage(sinksQuery.error)}
          />
        ) : sinks.length === 0 ? (
          <EmptyState
            icon={<CloudUploadOutlined className="u-text-xl" />}
            title={t('admin.audit_sinks.title')}
            description={t('admin.audit_sinks.empty')}
          />
        ) : (
          <div
            data-testid="audit-sinks-grid"
            className="u-grid u-gap-4" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))', alignContent: 'start' }}
          >
            {sinks.map((s) => (
              <SinkCard
                key={s.id}
                sink={s}
                onEdit={() => setEditing(s)}
                onTest={() => testMutation.mutate(s.id)}
                onDelete={() => deleteMutation.mutate(s.id)}
                testing={testMutation.isPending && testMutation.variables === s.id}
                deleting={deleteMutation.isPending && deleteMutation.variables === s.id}
              />
            ))}
          </div>
        )}
      </PageBody>

      <SinkFormModal
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
            createMutation.mutate(payload as CreateAuditSinkInput);
          }
        }}
        loading={createMutation.isPending || updateMutation.isPending}
      />
    </Page>
  );
}

function sinkIcon(type: AuditSinkType) {
  switch (type) {
    case 'SPLUNK_HEC':
      return <CloudServerOutlined className="u-text-xl" />;
    case 'SYSLOG_CEF':
      return <SendOutlined className="u-text-xl" />;
    case 'HTTPS_BATCH':
      return <CloudUploadOutlined className="u-text-xl" />;
    case 'S3_OBJECT_LOCK':
      return <FileProtectOutlined className="u-text-xl" />;
    default:
      return <DatabaseOutlined className="u-text-xl" />;
  }
}

function destinationSummary(sink: AuditSink): string {
  const cfg = sink.config;
  switch (sink.type) {
    case 'SPLUNK_HEC':
    case 'HTTPS_BATCH':
      return String(cfg.url ?? '—');
    case 'SYSLOG_CEF':
      return `${String(cfg.host ?? '—')}:${String(cfg.port ?? '')} (${String(cfg.protocol ?? '')})`;
    case 'S3_OBJECT_LOCK':
      return `s3://${String(cfg.bucket ?? '—')}/${String(cfg.prefix ?? 'audit/')}`;
    default:
      return '—';
  }
}

function SinkCard({
  sink,
  onEdit,
  onTest,
  onDelete,
  testing,
  deleting,
}: {
  sink: AuditSink;
  onEdit: () => void;
  onTest: () => void;
  onDelete: () => void;
  testing: boolean;
  deleting: boolean;
}) {
  const { t } = useTranslation();
  const lagging = sink.consecutive_failures > 0 || sink.behind_count > 0;
  const behindDisplay = sink.behind_count_capped
    ? t('admin.audit_sinks.behind_capped')
    : String(sink.behind_count);
  return (
    <div
      data-testid="audit-sink-card"
      className="af-box u-p-4"
    >
      <div className="u-flex u-items-start u-gap-3">
        <div
          className="u-flex u-items-center u-justify-center u-rounded-md u-bg-sunken u-shrink-0" style={{ width: 36, height: 36 }}
        >
          {sinkIcon(sink.type)}
        </div>
        <div className="u-flex-1 u-min-w-0">
          <div className="u-flex u-items-center u-gap-2">
            <div className="u-fw-600 u-text-md">{sink.name}</div>
            {!sink.enabled && (
              <Pill
                fg="var(--fg-muted)"
                bg="var(--status-neutral-bg)"
                border="var(--status-neutral-border)"
                size="sm"
              >
                {t('admin.audit_sinks.pill_disabled')}
              </Pill>
            )}
          </div>
          <div className="mono muted u-text-2xs u-mt-1">
            {auditSinkTypeLabel(t, sink.type)}
          </div>
        </div>
      </div>
      <div
        className="mono u-mt-3 u-text-xs u-leading-normal u-nowrap u-bg-sunken u-rounded u-overflow-hidden u-ellipsis u-p-2"
      >
        {destinationSummary(sink)}
      </div>
      <div
        data-testid="audit-sink-health"
        className="u-mt-3 u-grid u-cols-2 u-text-xs" style={{ gap: '6px 12px' }}
      >
        <HealthRow
          label={t('admin.audit_sinks.health_behind')}
          value={behindDisplay}
          emphasis={lagging}
        />
        <HealthRow
          label={t('admin.audit_sinks.health_failures')}
          value={String(sink.consecutive_failures)}
          emphasis={sink.consecutive_failures > 0}
        />
        <HealthRow
          label={t('admin.audit_sinks.health_last_success')}
          value={sink.last_success_at ? fmtDate(sink.last_success_at) : '—'}
        />
        <HealthRow
          label={t('admin.audit_sinks.health_next_retry')}
          value={sink.next_attempt_at ? fmtDate(sink.next_attempt_at) : '—'}
        />
        <HealthRow
          label={t('admin.audit_sinks.health_cursor')}
          value={sink.cursor_created_at ? fmtDate(sink.cursor_created_at) : '—'}
        />
        <HealthRow
          label={t('admin.audit_sinks.health_last_error')}
          value={
            sink.last_error ? (
              <Tooltip title={sink.last_error}>
                <span
                  className="u-nowrap u-inline-block u-overflow-hidden u-ellipsis" style={{ maxWidth: '100%', verticalAlign: 'bottom' }}
                >
                  {sink.last_error}
                </span>
              </Tooltip>
            ) : (
              '—'
            )
          }
          emphasis={!!sink.last_error}
        />
      </div>
      <div
        className="u-flex u-items-center u-gap-2 u-mt-3 u-pt-3 u-border-t"
      >
        <span className="mono muted u-text-2xs">
          {fmtDate(sink.created_at).split(',')[0]}
        </span>
        <div className="u-flex-1" />
        <Button size="small" icon={<PlayCircleOutlined />} onClick={onTest} loading={testing}>
          {t('common.test')}
        </Button>
        <Button size="small" icon={<EditOutlined />} onClick={onEdit}>
          {t('common.edit')}
        </Button>
        <Popconfirm
          title={t('admin.audit_sinks.delete_confirm_title')}
          description={t('admin.audit_sinks.delete_confirm_body', { name: sink.name })}
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
            aria-label={t('admin.audit_sinks.action_delete')}
          />
        </Popconfirm>
      </div>
    </div>
  );
}

function HealthRow({
  label,
  value,
  emphasis,
}: {
  label: string;
  value: ReactNode;
  emphasis?: boolean;
}) {
  return (
    <div className="u-min-w-0">
      <div className="muted u-text-2xs">
        {label}
      </div>
      <div
        className="mono u-text-xs u-nowrap u-overflow-hidden u-ellipsis"
        style={{ color: emphasis ? 'var(--danger)' : undefined }}
      >
        {value}
      </div>
    </div>
  );
}

function SinkFormModal({
  open,
  editing,
  onClose,
  onSubmit,
  loading,
}: {
  open: boolean;
  editing: AuditSink | null;
  onClose: () => void;
  onSubmit: (payload: CreateAuditSinkInput | UpdateAuditSinkInput) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<SinkFormValues>();
  const sinkType = Form.useWatch('type', form);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      const c = editing.config;
      form.setFieldsValue({
        name: editing.name,
        type: editing.type,
        enabled: editing.enabled,
        url: c.url as string | undefined,
        token: c.token as string | undefined,
        index: c.index as string | undefined,
        source: c.source as string | undefined,
        host: c.host as string | undefined,
        port: c.port as number | undefined,
        protocol: c.protocol as 'TCP' | 'TLS' | undefined,
        secret: c.secret as string | undefined,
        bucket: c.bucket as string | undefined,
        region: c.region as string | undefined,
        access_key_id: c.access_key_id as string | undefined,
        secret_access_key: c.secret_access_key as string | undefined,
        retention_days: c.retention_days as number | undefined,
        prefix: c.prefix as string | undefined,
        endpoint: c.endpoint as string | undefined,
        retention_mode: c.retention_mode as 'COMPLIANCE' | 'GOVERNANCE' | undefined,
        segment_max_age: c.segment_max_age as string | undefined,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ type: 'SPLUNK_HEC' });
    }
  }, [open, editing, form]);

  const onFinish = (values: SinkFormValues) => {
    const config: Record<string, unknown> = {};
    const setIf = (key: string, value: unknown) => {
      if (value !== undefined && value !== null && value !== '') config[key] = value;
    };
    if (values.type === 'SPLUNK_HEC') {
      setIf('url', values.url);
      // Pass through MASK to preserve the stored token, otherwise send the fresh value.
      setIf('token', values.token);
      setIf('index', values.index);
      setIf('source', values.source);
    } else if (values.type === 'SYSLOG_CEF') {
      setIf('host', values.host);
      setIf('port', values.port);
      setIf('protocol', values.protocol);
    } else if (values.type === 'HTTPS_BATCH') {
      setIf('url', values.url);
      setIf('secret', values.secret);
    } else {
      setIf('bucket', values.bucket);
      setIf('region', values.region);
      setIf('access_key_id', values.access_key_id);
      setIf('secret_access_key', values.secret_access_key);
      setIf('retention_days', values.retention_days);
      setIf('prefix', values.prefix);
      setIf('endpoint', values.endpoint);
      setIf('retention_mode', values.retention_mode);
      setIf('segment_max_age', values.segment_max_age);
    }
    if (editing) {
      onSubmit({
        name: values.name.trim(),
        enabled: values.enabled,
        config,
      });
    } else {
      onSubmit({
        name: values.name.trim(),
        type: values.type,
        config,
      });
    }
  };

  return (
    <Modal
      open={open}
      title={
        editing
          ? t('admin.audit_sinks.modal_edit_title', { name: editing.name })
          : t('admin.audit_sinks.modal_create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={editing ? t('admin.audit_sinks.save_update') : t('admin.audit_sinks.save_create')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
      width={640}
    >
      <Form<SinkFormValues> form={form} layout="vertical" onFinish={onFinish}>
        <Form.Item
          name="name"
          label={t('admin.audit_sinks.label_name')}
          rules={[{ required: true, max: 255, whitespace: true }]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item
          name="type"
          label={t('admin.audit_sinks.label_type')}
          rules={[{ required: true }]}
        >
          <Select disabled={!!editing} options={enumOptions(AUDIT_SINK_TYPES, auditSinkTypeLabel, t)} />
        </Form.Item>
        {editing && (
          <Form.Item
            name="enabled"
            label={t('admin.audit_sinks.label_enabled')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        )}

        {sinkType === 'SPLUNK_HEC' && (
          <>
            <Form.Item
              name="url"
              label={t('admin.audit_sinks.label_splunk_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input placeholder="https://splunk.example.com:8088/services/collector/event" />
            </Form.Item>
            <Form.Item
              name="token"
              label={t('admin.audit_sinks.label_splunk_token')}
              rules={[{ required: !editing }]}
            >
              <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
            </Form.Item>
            <div className="u-grid u-cols-2 u-gap-3">
              <Form.Item name="index" label={t('admin.audit_sinks.label_splunk_index')}>
                <Input />
              </Form.Item>
              <Form.Item name="source" label={t('admin.audit_sinks.label_splunk_source')}>
                <Input placeholder="accessflow" />
              </Form.Item>
            </div>
          </>
        )}

        {sinkType === 'SYSLOG_CEF' && (
          <div className="u-grid u-gap-3" style={{ gridTemplateColumns: '2fr 1fr 1fr' }}>
            <Form.Item
              name="host"
              label={t('admin.audit_sinks.label_syslog_host')}
              rules={[{ required: true }]}
            >
              <Input placeholder="siem.example.com" />
            </Form.Item>
            <Form.Item
              name="port"
              label={t('admin.audit_sinks.label_syslog_port')}
              rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}
            >
              <InputNumber min={1} max={65535} className="u-w-full" />
            </Form.Item>
            <Form.Item
              name="protocol"
              label={t('admin.audit_sinks.label_syslog_protocol')}
              rules={[{ required: true }]}
            >
              <Select
                options={[
                  { value: 'TCP', label: t('admin.audit_sinks.label_syslog_protocol_tcp') },
                  { value: 'TLS', label: t('admin.audit_sinks.label_syslog_protocol_tls') },
                ]}
              />
            </Form.Item>
          </div>
        )}

        {sinkType === 'HTTPS_BATCH' && (
          <>
            <Form.Item
              name="url"
              label={t('admin.audit_sinks.label_https_url')}
              rules={[{ required: true, type: 'url' }]}
            >
              <Input placeholder="https://collector.example.com/audit" />
            </Form.Item>
            <Form.Item
              name="secret"
              label={t('admin.audit_sinks.label_https_secret')}
              rules={[{ required: !editing }]}
            >
              <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
            </Form.Item>
          </>
        )}

        {sinkType === 'S3_OBJECT_LOCK' && (
          <>
            <div className="u-grid u-cols-2 u-gap-3">
              <Form.Item
                name="bucket"
                label={t('admin.audit_sinks.label_s3_bucket')}
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="region"
                label={t('admin.audit_sinks.label_s3_region')}
                rules={[{ required: true }]}
              >
                <Input placeholder="eu-central-1" />
              </Form.Item>
              <Form.Item
                name="access_key_id"
                label={t('admin.audit_sinks.label_s3_access_key_id')}
                rules={[{ required: true }]}
              >
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item
                name="secret_access_key"
                label={t('admin.audit_sinks.label_s3_secret_access_key')}
                rules={[{ required: !editing }]}
              >
                <Input.Password placeholder={editing ? MASK : ''} autoComplete="new-password" />
              </Form.Item>
              <Form.Item
                name="retention_days"
                label={t('admin.audit_sinks.label_s3_retention_days')}
                rules={[{ required: true, type: 'number', min: 1 }]}
              >
                <InputNumber min={1} className="u-w-full" />
              </Form.Item>
              <Form.Item
                name="retention_mode"
                label={t('admin.audit_sinks.label_s3_retention_mode')}
              >
                <Select
                  allowClear
                  placeholder={t('admin.audit_sinks.label_s3_retention_mode_compliance')}
                  options={[
                    {
                      value: 'COMPLIANCE',
                      label: t('admin.audit_sinks.label_s3_retention_mode_compliance'),
                    },
                    {
                      value: 'GOVERNANCE',
                      label: t('admin.audit_sinks.label_s3_retention_mode_governance'),
                    },
                  ]}
                />
              </Form.Item>
              <Form.Item name="prefix" label={t('admin.audit_sinks.label_s3_prefix')}>
                <Input placeholder="audit/" />
              </Form.Item>
              <Form.Item
                name="segment_max_age"
                label={t('admin.audit_sinks.label_s3_segment_max_age')}
              >
                <Input placeholder="PT15M" />
              </Form.Item>
            </div>
            <Form.Item name="endpoint" label={t('admin.audit_sinks.label_s3_endpoint')}>
              <Input placeholder="https://minio.internal:9000" />
            </Form.Item>
          </>
        )}
      </Form>
    </Modal>
  );
}
