import { useState } from 'react';
import {
  Alert,
  App,
  Button,
  DatePicker,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';
import {
  createReviewDelegation,
  listDelegateCandidates,
  listMyReviewDelegations,
  reviewDelegationKeys,
  revokeReviewDelegation,
} from '@/api/reviewDelegations';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import type { DelegationScopeKind, ReviewDelegation } from '@/types/api';
import { apiErrorMessage, apiErrorTraceId } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import { fmtDate } from '@/utils/dateFormat';

interface DelegationFormValues {
  delegate_user_id: string;
  scope_kind?: DelegationScopeKind | null;
  scope_id?: string | null;
  window: [Dayjs, Dayjs];
  reason?: string;
}

const STATUS_COLOR: Record<ReviewDelegation['status'], string> = {
  SCHEDULED: 'blue',
  ACTIVE: 'green',
  EXPIRED: 'default',
  REVOKED: 'default',
};

export function ReviewDelegationSection() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<DelegationFormValues>();
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);
  const scopeKind = Form.useWatch('scope_kind', form);

  const delegations = useQuery({
    queryKey: reviewDelegationKeys.mine(),
    queryFn: listMyReviewDelegations,
  });
  const candidates = useQuery({
    queryKey: reviewDelegationKeys.candidates(),
    queryFn: listDelegateCandidates,
  });
  // Only fetched once a datasource scope is actually chosen — most delegations are unscoped.
  const datasources = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
    enabled: scopeKind === 'DATASOURCE',
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: reviewDelegationKeys.all });

  const createMutation = useMutation({
    mutationFn: (values: DelegationFormValues) =>
      createReviewDelegation({
        delegateUserId: values.delegate_user_id,
        scopeKind: values.scope_kind ?? null,
        scopeId: values.scope_kind ? (values.scope_id ?? null) : null,
        reason: values.reason ?? null,
        startsAt: values.window[0].toISOString(),
        endsAt: values.window[1].toISOString(),
      }),
    onSuccess: () => {
      setError(null);
      form.resetFields();
      invalidate();
      message.success(t('profile.delegation.created'));
    },
    // Keep the server's detail — the backend localizes the exact rule that failed.
    onError: (err) =>
      setError({
        message: apiErrorMessage(err, () => t('profile.delegation.error_generic')),
        traceId: apiErrorTraceId(err),
      }),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeReviewDelegation(id),
    onSuccess: () => {
      setError(null);
      invalidate();
      message.success(t('profile.delegation.revoked'));
    },
    // Keep the server's detail — the backend localizes the exact rule that failed.
    onError: (err) =>
      setError({
        message: apiErrorMessage(err, () => t('profile.delegation.error_generic')),
        traceId: apiErrorTraceId(err),
      }),
  });

  const candidateOptions = (candidates.data ?? []).map((candidate) => ({
    value: candidate.id,
    label: candidate.display_name ?? candidate.email ?? candidate.id,
  }));

  const grantedColumns: ColumnsType<ReviewDelegation> = [
    {
      title: t('profile.delegation.col_delegate'),
      key: 'delegate',
      render: (_, row) => row.delegate.display_name ?? row.delegate.email,
    },
    {
      title: t('profile.delegation.col_scope'),
      key: 'scope',
      render: (_, row) => row.scope_name ?? t('profile.delegation.scope_all'),
    },
    {
      title: t('profile.delegation.col_window'),
      key: 'window',
      render: (_, row) => `${fmtDate(row.starts_at)} – ${fmtDate(row.ends_at)}`,
    },
    {
      title: t('profile.delegation.col_status'),
      key: 'status',
      render: (_, row) => (
        <Tag color={STATUS_COLOR[row.status]}>{t(`enums.delegation_status.${row.status}`)}</Tag>
      ),
    },
    {
      title: t('profile.delegation.col_actions'),
      key: 'actions',
      render: (_, row) =>
        row.status === 'SCHEDULED' || row.status === 'ACTIVE' ? (
          <Popconfirm
            title={t('profile.delegation.revoke_confirm')}
            onConfirm={() => revokeMutation.mutate(row.id)}
          >
            <Button size="small" danger loading={revokeMutation.isPending}>
              {t('profile.delegation.revoke')}
            </Button>
          </Popconfirm>
        ) : null,
    },
  ];

  const receivedColumns: ColumnsType<ReviewDelegation> = [
    {
      title: t('profile.delegation.col_delegator'),
      key: 'delegator',
      render: (_, row) => row.delegator.display_name ?? row.delegator.email,
    },
    grantedColumns[1] as ColumnsType<ReviewDelegation>[number],
    grantedColumns[2] as ColumnsType<ReviewDelegation>[number],
    grantedColumns[3] as ColumnsType<ReviewDelegation>[number],
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Alert type="info" showIcon message={t('profile.delegation.explainer')} />

      {error && (
        <Alert
          type="error"
          showIcon
          closable
          message={error.message}
          description={error.traceId ? <TraceIdFooter traceId={error.traceId} /> : undefined}
          onClose={() => setError(null)}
        />
      )}

      <Form<DelegationFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) => createMutation.mutate(values)}
      >
        <Form.Item
          name="delegate_user_id"
          label={t('profile.delegation.label_delegate')}
          rules={[{ required: true, message: t('profile.delegation.validation_delegate') }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            loading={candidates.isLoading}
            options={candidateOptions}
            placeholder={t('profile.delegation.select_delegate')}
          />
        </Form.Item>

        <Form.Item name="scope_kind" label={t('profile.delegation.label_scope_kind')}>
          <Select
            allowClear
            onChange={() => form.setFieldValue('scope_id', undefined)}
            placeholder={t('profile.delegation.scope_all')}
            options={[{ value: 'DATASOURCE', label: t('enums.delegation_scope.DATASOURCE') }]}
          />
        </Form.Item>

        {scopeKind === 'DATASOURCE' && (
          <Form.Item
            name="scope_id"
            label={t('profile.delegation.label_scope')}
            rules={[{ required: true, message: t('profile.delegation.validation_scope') }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              loading={datasources.isLoading}
              options={(datasources.data?.content ?? []).map((ds) => ({
                value: ds.id,
                label: ds.name,
              }))}
            />
          </Form.Item>
        )}

        <Form.Item
          name="window"
          label={t('profile.delegation.label_window')}
          rules={[{ required: true, message: t('profile.delegation.validation_window') }]}
        >
          <DatePicker.RangePicker showTime style={{ width: '100%' }} />
        </Form.Item>

        <Form.Item
          name="reason"
          label={t('profile.delegation.label_reason')}
          rules={[{ max: 500, message: t('profile.delegation.validation_reason') }]}
        >
          <Input.TextArea
            rows={2}
            maxLength={500}
            showCount
            placeholder={t('profile.delegation.reason_placeholder')}
          />
        </Form.Item>

        <Form.Item>
          <Button type="primary" htmlType="submit" loading={createMutation.isPending}>
            {t('profile.delegation.submit')}
          </Button>
        </Form.Item>
      </Form>

      <Table<ReviewDelegation>
        rowKey="id"
        size="small"
        title={() => t('profile.delegation.granted_title')}
        loading={delegations.isLoading}
        columns={grantedColumns}
        dataSource={delegations.data?.granted ?? []}
        pagination={false}
        locale={{ emptyText: t('profile.delegation.granted_empty') }}
      />

      <Table<ReviewDelegation>
        rowKey="id"
        size="small"
        title={() => t('profile.delegation.received_title')}
        loading={delegations.isLoading}
        columns={receivedColumns}
        dataSource={delegations.data?.received ?? []}
        pagination={false}
        locale={{ emptyText: t('profile.delegation.received_empty') }}
      />
    </Space>
  );
}
