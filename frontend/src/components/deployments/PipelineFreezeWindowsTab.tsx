import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  DatePicker,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Switch,
  Table,
  Tag,
  TimePicker,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  createDeploymentFreezeWindow,
  deleteDeploymentFreezeWindow,
  deploymentFreezeWindowKeys,
  listDeploymentFreezeWindows,
  updateDeploymentFreezeWindow,
} from '@/api/deploymentFreezeWindows';
import { deploymentPipelineKeys, listDeploymentEnvironments } from '@/api/deploymentPipelines';
import {
  FREEZE_BEHAVIORS,
  enumOptions,
  freezeBehaviorLabel,
  isoWeekdayLabel,
} from '@/utils/enumLabels';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  freezeWindowSummary,
  timezoneOptions,
  toWireInput,
  windowMode,
  type FreezeWindowMode,
} from './freezeWindowForm';
import { buildWeekSegments } from './freezeWindowCalendar';
import type { DeploymentFreezeWindow, FreezeBehavior } from '@/types/api';

// The list endpoint has no pipeline filter — fetch a full page (the documented max) and
// filter client-side.
const FETCH_SIZE = 100;
const ISO_DAYS = [1, 2, 3, 4, 5, 6, 7];
const MINUTES_PER_DAY = 24 * 60;

interface FreezeFormValues {
  mode: FreezeWindowMode;
  scoped: boolean;
  environment_id?: string | null;
  starts_at?: Dayjs | null;
  ends_at?: Dayjs | null;
  days_of_week?: number[];
  start_time?: Dayjs | null;
  end_time?: Dayjs | null;
  timezone?: string | null;
  behavior: FreezeBehavior;
  reason?: string | null;
  enabled: boolean;
}

/** Dumb renderer for the recurring-week strip; segments come from the pure calendar helper. */
function WeekStrip({ windows }: { windows: DeploymentFreezeWindow[] }) {
  const { t } = useTranslation();
  const segments = useMemo(() => buildWeekSegments(windows), [windows]);
  if (segments.length === 0) return null;
  return (
    <div>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>
        {t('deploygov.freezeWindows.weekTitle')}
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(7, 1fr)',
          gap: 4,
        }}
        data-testid="freeze-week-strip"
      >
        {ISO_DAYS.map((day) => (
          <div key={day}>
            <div className="muted" style={{ fontSize: 11, textAlign: 'center', marginBottom: 2 }}>
              {isoWeekdayLabel(t, day)}
            </div>
            <div
              style={{
                position: 'relative',
                height: 96,
                background: 'var(--bg-sunken)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                overflow: 'hidden',
              }}
            >
              {segments
                .filter((s) => s.day === day)
                .map((s, i) => (
                  <div
                    key={`${s.windowId}-${i}`}
                    title={`${String(Math.floor(s.startMinutes / 60)).padStart(2, '0')}:${String(
                      s.startMinutes % 60,
                    ).padStart(2, '0')}–${String(Math.floor(s.endMinutes / 60)).padStart(
                      2,
                      '0',
                    )}:${String(s.endMinutes % 60).padStart(2, '0')}`}
                    style={{
                      position: 'absolute',
                      left: 2,
                      right: 2,
                      top: `${(s.startMinutes / MINUTES_PER_DAY) * 100}%`,
                      height: `${((s.endMinutes - s.startMinutes) / MINUTES_PER_DAY) * 100}%`,
                      minHeight: 3,
                      borderRadius: 2,
                      background:
                        s.behavior === 'REJECT' ? 'var(--risk-crit-bg)' : 'var(--status-warn-bg)',
                      border: `1px solid ${
                        s.behavior === 'REJECT'
                          ? 'var(--risk-crit-border)'
                          : 'var(--status-warn-border)'
                      }`,
                    }}
                  />
                ))}
            </div>
          </div>
        ))}
      </div>
      <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
        {t('deploygov.freezeWindows.weekHint')}
      </div>
    </div>
  );
}

export function PipelineFreezeWindowsTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [modalFor, setModalFor] = useState<'create' | DeploymentFreezeWindow | null>(null);
  const [form] = Form.useForm<FreezeFormValues>();
  const mode: FreezeWindowMode = Form.useWatch('mode', form) ?? 'one_off';
  const scoped: boolean = Form.useWatch('scoped', form) ?? true;

  const windowsQuery = useQuery({
    queryKey: deploymentFreezeWindowKeys.list({ size: FETCH_SIZE }),
    queryFn: () => listDeploymentFreezeWindows({ size: FETCH_SIZE }),
  });
  const environmentsQuery = useQuery({
    queryKey: deploymentPipelineKeys.environments(pipelineId),
    queryFn: () => listDeploymentEnvironments(pipelineId),
  });

  const visible = useMemo(
    () =>
      (windowsQuery.data?.content ?? []).filter(
        (w) => w.pipeline_id === pipelineId || w.pipeline_id == null,
      ),
    [windowsQuery.data, pipelineId],
  );

  const environmentName = (id: string | null) =>
    id == null
      ? t('deploygov.freezeWindows.environmentAll')
      : environmentsQuery.data?.find((e) => e.id === id)?.name ?? id;

  useEffect(() => {
    if (modalFor === null) return;
    if (modalFor === 'create') {
      form.setFieldsValue({
        mode: 'one_off',
        scoped: true,
        environment_id: null,
        starts_at: null,
        ends_at: null,
        days_of_week: [],
        start_time: null,
        end_time: null,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        behavior: 'HOLD',
        reason: null,
        enabled: true,
      });
    } else {
      form.setFieldsValue({
        mode: windowMode(modalFor),
        scoped: modalFor.pipeline_id != null,
        environment_id: modalFor.environment_id,
        starts_at: modalFor.starts_at ? dayjs(modalFor.starts_at) : null,
        ends_at: modalFor.ends_at ? dayjs(modalFor.ends_at) : null,
        days_of_week: [...modalFor.days_of_week],
        start_time: modalFor.start_time ? dayjs(modalFor.start_time, 'HH:mm:ss') : null,
        end_time: modalFor.end_time ? dayjs(modalFor.end_time, 'HH:mm:ss') : null,
        timezone: modalFor.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone,
        behavior: modalFor.behavior,
        reason: modalFor.reason,
        enabled: modalFor.enabled,
      });
    }
  }, [modalFor, form]);

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: deploymentFreezeWindowKeys.lists() });

  const saveMutation = useMutation({
    mutationFn: (values: FreezeFormValues) => {
      const input = toWireInput({
        mode: values.mode,
        pipeline_id: values.scoped ? pipelineId : null,
        environment_id: values.scoped ? values.environment_id ?? null : null,
        starts_at: values.starts_at ? values.starts_at.toISOString() : null,
        ends_at: values.ends_at ? values.ends_at.toISOString() : null,
        days_of_week: values.days_of_week ?? [],
        start_time: values.start_time ? values.start_time.format('HH:mm') : null,
        end_time: values.end_time ? values.end_time.format('HH:mm') : null,
        timezone: values.timezone ?? null,
        behavior: values.behavior,
        reason: values.reason?.trim() || null,
        enabled: values.enabled,
      });
      return modalFor === 'create'
        ? createDeploymentFreezeWindow(input)
        : updateDeploymentFreezeWindow((modalFor as DeploymentFreezeWindow).id, input);
    },
    onSuccess: () => {
      message.success(
        modalFor === 'create'
          ? t('deploygov.freezeWindows.created')
          : t('deploygov.freezeWindows.updated'),
      );
      setModalFor(null);
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDeploymentFreezeWindow(id),
    onSuccess: () => {
      message.success(t('deploygov.freezeWindows.deleted'));
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const columns: TableColumnsType<DeploymentFreezeWindow> = [
    {
      title: t('deploygov.freezeWindows.scope'),
      key: 'scope',
      width: 150,
      render: (_v, w) =>
        w.pipeline_id == null ? (
          <Tag color="blue">{t('deploygov.freezeWindows.scopeGlobal')}</Tag>
        ) : (
          <span style={{ fontSize: 12 }}>{environmentName(w.environment_id)}</span>
        ),
    },
    {
      title: t('deploygov.freezeWindows.schedule'),
      key: 'schedule',
      render: (_v, w) => <span style={{ fontSize: 12 }}>{freezeWindowSummary(t, w)}</span>,
    },
    {
      title: t('deploygov.freezeWindows.behavior'),
      dataIndex: 'behavior',
      width: 130,
      render: (b: FreezeBehavior) => (
        <Tag color={b === 'REJECT' ? 'red' : 'orange'}>{freezeBehaviorLabel(t, b)}</Tag>
      ),
    },
    {
      title: t('deploygov.freezeWindows.reason'),
      dataIndex: 'reason',
      ellipsis: true,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: t('deploygov.freezeWindows.enabled'),
      dataIndex: 'enabled',
      width: 100,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : <span className="muted">{t('deploygov.freezeWindows.disabled')}</span>),
    },
    {
      title: '',
      key: 'actions',
      width: 160,
      render: (_v, row) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button size="small" onClick={() => setModalFor(row)}>
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('deploygov.freezeWindows.deleteConfirm')}
            okText={t('common.delete')}
            cancelText={t('common.cancel')}
            onConfirm={() => deleteMutation.mutate(row.id)}
          >
            <Button size="small" danger>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 960 }}>
      <div className="muted" style={{ fontSize: 12, lineHeight: 1.55 }}>
        {t('deploygov.freezeWindows.hint')}
      </div>
      <div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalFor('create')}>
          {t('deploygov.freezeWindows.add')}
        </Button>
      </div>
      <Table<DeploymentFreezeWindow>
        rowKey="id"
        size="small"
        pagination={false}
        loading={windowsQuery.isLoading}
        dataSource={visible}
        locale={{ emptyText: t('deploygov.freezeWindows.empty') }}
        columns={columns}
      />
      <WeekStrip windows={visible} />
      <Modal
        open={modalFor !== null}
        title={
          modalFor === 'create'
            ? t('deploygov.freezeWindows.createTitle')
            : t('deploygov.freezeWindows.editTitle')
        }
        onCancel={() => setModalFor(null)}
        okText={t('common.save')}
        confirmLoading={saveMutation.isPending}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form<FreezeFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          <Form.Item name="mode" label={t('deploygov.freezeWindows.modeLabel')}>
            <Radio.Group
              options={[
                { value: 'one_off', label: t('deploygov.freezeWindows.modeOneOff') },
                { value: 'recurring', label: t('deploygov.freezeWindows.modeRecurring') },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="scoped"
            label={t('deploygov.freezeWindows.scope')}
            valuePropName="checked"
            tooltip={t('deploygov.freezeWindows.scopeThisPipeline')}
          >
            <Switch
              size="small"
              checkedChildren={t('deploygov.freezeWindows.scopeThisPipeline')}
              unCheckedChildren={t('deploygov.freezeWindows.scopeGlobal')}
            />
          </Form.Item>
          {scoped && (
            <Form.Item name="environment_id" label={t('deploygov.freezeWindows.labelEnvironment')}>
              <Select
                allowClear
                placeholder={t('deploygov.freezeWindows.environmentAll')}
                loading={environmentsQuery.isLoading}
                options={(environmentsQuery.data ?? []).map((e) => ({
                  value: e.id,
                  label: e.name,
                }))}
              />
            </Form.Item>
          )}
          {mode === 'one_off' ? (
            <>
              <Form.Item
                name="starts_at"
                label={t('deploygov.freezeWindows.labelStartsAt')}
                rules={[{ required: true }]}
              >
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="ends_at"
                label={t('deploygov.freezeWindows.labelEndsAt')}
                dependencies={['starts_at']}
                rules={[
                  { required: true },
                  ({ getFieldValue }) => ({
                    validator(_rule, value: Dayjs | null) {
                      const starts: Dayjs | null = getFieldValue('starts_at');
                      if (!value || !starts || value.isAfter(starts)) return Promise.resolve();
                      return Promise.reject(
                        new Error(t('deploygov.freezeWindows.endsAfterStarts')),
                      );
                    },
                  }),
                ]}
              >
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item
                name="days_of_week"
                label={t('deploygov.freezeWindows.labelDays')}
                rules={[{ required: true }]}
              >
                <Select
                  mode="multiple"
                  options={ISO_DAYS.map((d) => ({ value: d, label: isoWeekdayLabel(t, d) }))}
                />
              </Form.Item>
              <Form.Item
                name="start_time"
                label={t('deploygov.freezeWindows.labelStartTime')}
                rules={[{ required: true }]}
              >
                <TimePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="end_time"
                label={t('deploygov.freezeWindows.labelEndTime')}
                dependencies={['start_time']}
                rules={[
                  { required: true },
                  ({ getFieldValue }) => ({
                    validator(_rule, value: Dayjs | null) {
                      const start: Dayjs | null = getFieldValue('start_time');
                      if (!value || !start || value.format('HH:mm') !== start.format('HH:mm')) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error(t('deploygov.freezeWindows.timesDiffer')));
                    },
                  }),
                ]}
              >
                <TimePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="timezone"
                label={t('deploygov.freezeWindows.labelTimezone')}
                rules={[{ required: true }]}
              >
                <Select
                  showSearch
                  options={timezoneOptions().map((z) => ({ value: z, label: z }))}
                />
              </Form.Item>
            </>
          )}
          <Form.Item
            name="behavior"
            label={t('deploygov.freezeWindows.labelBehavior')}
            rules={[{ required: true }]}
          >
            <Select options={enumOptions(FREEZE_BEHAVIORS, freezeBehaviorLabel, t)} />
          </Form.Item>
          <Form.Item name="reason" label={t('deploygov.freezeWindows.labelReason')}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('deploygov.freezeWindows.labelEnabled')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
