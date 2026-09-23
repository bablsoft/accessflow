import { useMemo, useState } from 'react';
import { App, Button, Card, Form, Input, Modal, Select, Skeleton, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { LadderStrip } from '@/components/schemaChange/LadderStrip';
import {
  createSchemaChangeSet,
  listSchemaChangePipelines,
  listSchemaChangeSets,
  schemaChangeKeys,
} from '@/api/schemaChange';
import type { CreateSchemaChangeSetInput, SchemaChangeSet, SchemaChangeSetStatus } from '@/types/api';
import { enumOptions, SCHEMA_CHANGE_SET_STATUSES, schemaChangeSetStatusLabel } from '@/utils/enumLabels';
import { schemaChangeSetStatusColor } from '@/utils/statusColors';
import { timeAgo } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  SCHEMA_CHANGE_DESCRIPTION_MAX,
  SCHEMA_CHANGE_NAME_MAX,
  SCHEMA_CHANGE_NAME_MIN,
} from '@/utils/schemaChange';

const PAGE_SIZE = 20;

export default function SchemaChangeSetListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<CreateSchemaChangeSetInput>();

  const [pipeline, setPipeline] = useState<string>('all');
  const [status, setStatus] = useState<SchemaChangeSetStatus | 'all'>('all');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);

  const filters = useMemo(
    () => ({
      pipeline_id: pipeline === 'all' ? undefined : pipeline,
      status: status === 'all' ? undefined : status,
      page,
      size: PAGE_SIZE,
    }),
    [pipeline, status, page],
  );

  const listQuery = useQuery({
    queryKey: schemaChangeKeys.list(filters),
    queryFn: () => listSchemaChangeSets(filters),
  });
  const pipelinesQuery = useQuery({
    queryKey: schemaChangeKeys.pipelines(),
    queryFn: listSchemaChangePipelines,
  });

  const pipelineNames = useMemo(
    () => new Map((pipelinesQuery.data ?? []).map((p) => [p.id, p.name])),
    [pipelinesQuery.data],
  );
  const pipelineOptions = (pipelinesQuery.data ?? []).map((p) => ({ value: p.id, label: p.name }));
  // Filtering shows every pipeline (old sets stay findable); a new set only targets an active one.
  const activePipelineOptions = (pipelinesQuery.data ?? [])
    .filter((p) => p.active)
    .map((p) => ({ value: p.id, label: p.name }));

  const createMutation = useMutation({
    mutationFn: createSchemaChangeSet,
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.lists() });
      message.success(t('schemaChange.list.created'));
      setCreating(false);
      form.resetFields();
      navigate(`/schema-change-sets/${created.id}`);
    },
    onError: (err) =>
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('schemaChange.list.createError'))),
  });

  const columns: TableColumnsType<SchemaChangeSet> = [
    {
      title: t('schemaChange.list.name'),
      dataIndex: 'name',
      render: (name: string) => <strong>{name}</strong>,
    },
    {
      title: t('schemaChange.list.pipeline'),
      dataIndex: 'pipeline_id',
      width: 180,
      render: (id: string) => pipelineNames.get(id) ?? <span className="mono muted">{id}</span>,
    },
    {
      title: t('schemaChange.list.status'),
      dataIndex: 'status',
      width: 120,
      render: (s: SchemaChangeSetStatus) => {
        const color = schemaChangeSetStatusColor(s);
        return (
          <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
            {schemaChangeSetStatusLabel(t, s)}
          </Tag>
        );
      },
    },
    {
      title: t('schemaChange.list.statements'),
      key: 'statements',
      width: 110,
      render: (_v, r) => <span className="mono">{r.statements.length}</span>,
    },
    {
      title: t('schemaChange.list.ladder'),
      key: 'ladder',
      render: (_v, r) => <LadderStrip changeSetId={r.id} />,
    },
    {
      title: t('schemaChange.list.updated'),
      dataIndex: 'updated_at',
      width: 120,
      render: (v: string) => (
        <span className="muted" style={{ fontSize: 12 }}>
          {timeAgo(v)}
        </span>
      ),
    },
  ];

  const rows = listQuery.data?.content ?? [];
  const filtered = pipeline !== 'all' || status !== 'all';

  let body;
  if (listQuery.isLoading) {
    body = (
      <div style={{ padding: 16 }} data-testid="schema-change-list-loading">
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  } else if (listQuery.isError) {
    body = (
      <Card size="small" style={{ margin: 16 }}>
        <EmptyState
          title={t('schemaChange.list.loadError')}
          description={apiErrorMessage(listQuery.error, () => t('schemaChange.list.loadError'))}
          action={<Button onClick={() => void listQuery.refetch()}>{t('common.retry')}</Button>}
        />
      </Card>
    );
  } else if (rows.length === 0 && !filtered && page === 0) {
    body = (
      <Card size="small" style={{ margin: 16 }}>
        <EmptyState
          title={t('schemaChange.list.emptyTitle')}
          description={t('schemaChange.list.emptyDescription')}
          action={
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('schemaChange.list.create')}
            </Button>
          }
        />
      </Card>
    );
  } else {
    body = (
      <Table<SchemaChangeSet>
        rowKey="id"
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('schemaChange.list.emptyFiltered') }}
        onRow={(row) => ({
          onClick: () => navigate(`/schema-change-sets/${row.id}`),
          style: { cursor: 'pointer' },
        })}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: listQuery.data?.total_elements ?? 0,
          showSizeChanger: false,
          onChange: (p) => setPage(p - 1),
        }}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('schemaChange.list.title')}
        subtitle={t('schemaChange.list.subtitle')}
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            {t('schemaChange.list.create')}
          </Button>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          background: 'var(--bg-elev)',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        <Select
          value={pipeline}
          aria-label={t('schemaChange.list.pipeline')}
          onChange={(v) => {
            setPipeline(v);
            setPage(0);
          }}
          options={[{ value: 'all', label: t('schemaChange.list.allPipelines') }, ...pipelineOptions]}
          style={{ width: 220 }}
        />
        <Select
          value={status}
          aria-label={t('schemaChange.list.status')}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('schemaChange.list.allStatuses') },
            ...enumOptions(SCHEMA_CHANGE_SET_STATUSES, schemaChangeSetStatusLabel, t),
          ]}
          style={{ width: 160 }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('schemaChange.list.count', { total: listQuery.data?.total_elements ?? 0 })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>{body}</div>

      <Modal
        open={creating}
        title={t('schemaChange.list.createTitle')}
        okText={t('schemaChange.list.createSubmit')}
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending}
        onCancel={() => setCreating(false)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form<CreateSchemaChangeSetInput>
          form={form}
          name="schema-change-create"
          layout="vertical"
          onFinish={(values) =>
            createMutation.mutate({
              pipeline_id: values.pipeline_id,
              name: values.name.trim(),
              description: values.description?.trim() || null,
            })
          }
        >
          <Form.Item
            name="pipeline_id"
            label={t('schemaChange.form.pipeline')}
            extra={t('schemaChange.form.pipelineHint')}
            rules={[{ required: true, message: t('schemaChange.form.pipelineRequired') }]}
          >
            <Select
              options={activePipelineOptions}
              loading={pipelinesQuery.isLoading}
              notFoundContent={t('schemaChange.form.noPipelines')}
            />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('schemaChange.form.name')}
            rules={[
              { required: true, whitespace: true, message: t('schemaChange.form.nameRequired') },
              {
                min: SCHEMA_CHANGE_NAME_MIN,
                max: SCHEMA_CHANGE_NAME_MAX,
                message: t('schemaChange.form.nameSize', {
                  min: SCHEMA_CHANGE_NAME_MIN,
                  max: SCHEMA_CHANGE_NAME_MAX,
                }),
              },
            ]}
          >
            <Input maxLength={SCHEMA_CHANGE_NAME_MAX} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('schemaChange.form.description')}
            rules={[
              {
                max: SCHEMA_CHANGE_DESCRIPTION_MAX,
                message: t('schemaChange.form.descriptionMax', { max: SCHEMA_CHANGE_DESCRIPTION_MAX }),
              },
            ]}
          >
            <Input.TextArea rows={3} maxLength={SCHEMA_CHANGE_DESCRIPTION_MAX} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
