import { useMemo, useState } from 'react';
import { App, Button, Form, Input, Select, Skeleton, Table, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  cancelErasure,
  lifecycleKeys,
  listMyErasures,
  submitErasure,
  type ErasureListFilters,
} from '@/api/lifecycle';
import { listDatasources } from '@/api/datasources';
import { ErasureConfigForm } from '@/components/lifecycle/ErasureConfigForm';
import {
  configToPayload,
  hasErasureScope,
  type ErasureConfigFormValues,
} from '@/pages/lifecycle/erasureConfigForm';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import {
  LIFECYCLE_SUBJECT_TYPES,
  enumOptions,
  erasureStatusLabel,
  lifecycleSubjectTypeLabel,
} from '@/utils/enumLabels';
import { erasureStatusTagColor } from '@/utils/erasureStatus';
import type { ErasureRequest, LifecycleSubjectType, SubmitErasureRequest } from '@/types/api';

const PAGE_SIZE = 20;

interface ErasureFormValues extends ErasureConfigFormValues {
  datasource_id: string;
  subject_type?: LifecycleSubjectType;
  subject_identifier?: string;
  reason?: string;
}

export default function ErasureSubmitPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ErasureFormValues>();
  const [page, setPage] = useState(0);

  const filters: ErasureListFilters = useMemo(() => ({ page, size: PAGE_SIZE }), [page]);

  const datasourcesQuery = useQuery({
    queryKey: ['datasources', 'list', { page: 0, size: 100 }],
    queryFn: () => listDatasources({ page: 0, size: 100 }),
  });

  const mineQuery = useQuery({
    queryKey: lifecycleKeys.erasureMine(filters),
    queryFn: () => listMyErasures(filters),
  });

  const submitMutation = useMutation({
    mutationFn: (body: SubmitErasureRequest) => submitErasure(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: lifecycleKeys.erasures() });
      message.success(t('lifecycle.erasure.submit_success'));
      form.resetFields();
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const cancelMutation = useMutation({
    mutationFn: (id: string) => cancelErasure(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: lifecycleKeys.erasures() });
      message.success(t('lifecycle.erasure.cancel_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const datasourceOptions = (datasourcesQuery.data?.content ?? []).map((d) => ({
    value: d.id,
    label: d.name,
  }));
  const requests = mineQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('lifecycle.erasure.title')} subtitle={t('lifecycle.erasure.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 28px', maxWidth: 1100 }}>
        <Form<ErasureFormValues>
          form={form}
          layout="vertical"
          initialValues={{ subject_type: 'EMAIL' }}
          style={{ maxWidth: 640, marginBottom: 32 }}
          onValuesChange={() => {
            // Nested Form.List edits don't always re-trigger `dependencies`-based rules;
            // re-validate the two cross-field anchors so a shown error clears as soon as
            // the user fixes any contributing field. `dirty` skips untouched fields.
            void form
              .validateFields(['subject_identifier', 'target_table'], { dirty: true })
              .catch(() => undefined);
          }}
          onFinish={(values) => {
            const config = configToPayload(values);
            const subjectId = values.subject_identifier?.trim() || null;
            submitMutation.mutate({
              datasource_id: values.datasource_id,
              subject_type: subjectId ? values.subject_type : null,
              subject_identifier: subjectId,
              target_table: config.target_table,
              target_columns: config.target_columns,
              conditions: config.conditions,
              raw_where: config.raw_where,
              reason: values.reason?.trim() || null,
            });
          }}
        >
          <Form.Item
            name="datasource_id"
            label={t('lifecycle.erasure.label_datasource')}
            rules={[{ required: true, message: t('validation.lifecycle_datasource_required') }]}
          >
            <Select
              placeholder={t('lifecycle.policies.datasource_placeholder')}
              loading={datasourcesQuery.isLoading}
              options={datasourceOptions}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item
            name="subject_type"
            label={t('lifecycle.erasure.label_subject_type')}
          >
            <Select options={enumOptions(LIFECYCLE_SUBJECT_TYPES, lifecycleSubjectTypeLabel, t)} />
          </Form.Item>
          <Form.Item
            name="subject_identifier"
            label={t('lifecycle.erasure.label_subject_identifier')}
            dependencies={['conditions', 'raw_where']}
            rules={[
              { max: 255, message: t('validation.lifecycle_subject_identifier_size') },
              ({ getFieldValue }) => ({
                validator: () =>
                  hasErasureScope({
                    subject_identifier: getFieldValue('subject_identifier') as string | undefined,
                    conditions: getFieldValue('conditions'),
                    raw_where: getFieldValue('raw_where') as string | undefined,
                  })
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(t('validation.lifecycle_erasure_scope_required')),
                      ),
              }),
            ]}
            tooltip={t('lifecycle.erasure.subject_help')}
          >
            <Input maxLength={255} placeholder="user@example.com" />
          </Form.Item>
          <ErasureConfigForm form={form} requireTargetTableWithConditions />
          <Form.Item
            name="reason"
            label={t('lifecycle.erasure.label_reason')}
            rules={[{ max: 2000, message: t('validation.lifecycle_reason_max') }]}
          >
            <Input.TextArea rows={2} maxLength={2000} showCount />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitMutation.isPending}>
            {t('lifecycle.erasure.submit')}
          </Button>
        </Form>

        <h3 style={{ fontSize: 14, marginBottom: 12 }}>{t('lifecycle.erasure.mine_title')}</h3>
        {mineQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : requests.length === 0 ? (
          <EmptyState
            title={t('lifecycle.erasure.mine_empty_title')}
            description={t('lifecycle.erasure.mine_empty_description')}
          />
        ) : (
          <Table<ErasureRequest>
            rowKey="id"
            size="middle"
            dataSource={requests}
            scroll={{ x: 'max-content' }}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: mineQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('lifecycle.erasure.col_subject'),
                dataIndex: 'subject_identifier',
                render: (v: string | null, r) => (
                  <div>
                    <div style={{ fontSize: 13 }}>{v ?? r.target_table ?? '—'}</div>
                    {r.datasource_name && (
                      <div className="mono muted" style={{ fontSize: 11 }}>
                        {r.datasource_name}
                      </div>
                    )}
                  </div>
                ),
              },
              {
                title: t('lifecycle.erasure.col_status'),
                dataIndex: 'status',
                width: 150,
                render: (status: ErasureRequest['status']) => (
                  <Tag color={erasureStatusTagColor(status)}>{erasureStatusLabel(t, status)}</Tag>
                ),
              },
              {
                title: t('lifecycle.erasure.col_created'),
                dataIndex: 'created_at',
                width: 170,
                render: (v: string) => <span className="muted">{fmtDate(v)}</span>,
              },
              {
                title: t('lifecycle.policies.col_actions'),
                key: 'actions',
                width: 110,
                align: 'right' as const,
                render: (_v, r) =>
                  r.status === 'PENDING_SCOPE_AI' || r.status === 'PENDING_REVIEW' ? (
                    <Button
                      size="small"
                      danger
                      loading={cancelMutation.isPending && cancelMutation.variables === r.id}
                      onClick={() => cancelMutation.mutate(r.id)}
                    >
                      {t('lifecycle.erasure.cancel')}
                    </Button>
                  ) : null,
              },
            ]}
          />
        )}
      </div>
    </div>
  );
}
