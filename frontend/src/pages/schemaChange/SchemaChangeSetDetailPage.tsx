import { useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Skeleton,
  Space,
  Table,
  Tag,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { EditOutlined, InboxOutlined, DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { PromotionLadder } from '@/components/schemaChange/PromotionLadder';
import {
  StatementListEditor,
  type StatementFormValue,
} from '@/components/schemaChange/StatementListEditor';
import {
  cancelSchemaChangePromotion,
  deleteSchemaChangeSet,
  getSchemaChangeLadder,
  getSchemaChangeSet,
  listSchemaChangePipelines,
  listSchemaChangePromotions,
  promoteSchemaChangeSet,
  replaceSchemaChangeSetStatements,
  schemaChangeKeys,
  updateSchemaChangeSet,
} from '@/api/schemaChange';
import type { SchemaChangePromotion, UpdateSchemaChangeSetInput } from '@/types/api';
import { schemaChangePromotionStatusLabel, schemaChangeSetStatusLabel } from '@/utils/enumLabels';
import { schemaChangePromotionStatusColor, schemaChangeSetStatusColor } from '@/utils/statusColors';
import { fmtDate } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  SCHEMA_CHANGE_DESCRIPTION_MAX,
  SCHEMA_CHANGE_MAX_STATEMENTS,
  SCHEMA_CHANGE_NAME_MAX,
  SCHEMA_CHANGE_NAME_MIN,
  findingsByStatement,
  freezingPromotion,
  statementProblemsFromError,
  type StatementProblem,
} from '@/utils/schemaChange';

const LADDER_POLL_MS = 10_000;

interface StatementsForm {
  statements: StatementFormValue[];
}

export default function SchemaChangeSetDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [statementsForm] = Form.useForm<StatementsForm>();
  const [detailsForm] = Form.useForm<UpdateSchemaChangeSetInput>();
  const [problems, setProblems] = useState<Record<number, StatementProblem[]>>({});
  const [editingDetails, setEditingDetails] = useState(false);

  const setQuery = useQuery({
    queryKey: schemaChangeKeys.detail(id),
    queryFn: () => getSchemaChangeSet(id),
    enabled: !!id,
  });
  const ladderQuery = useQuery({
    queryKey: schemaChangeKeys.ladder(id),
    queryFn: () => getSchemaChangeLadder(id),
    enabled: !!id,
    // The status push reaches only the promoter; anyone else watching follows an open promotion here.
    refetchInterval: (query) =>
      query.state.data?.rungs.some((r) => r.state === 'IN_PROGRESS') ? LADDER_POLL_MS : false,
  });
  const promotionsQuery = useQuery({
    queryKey: schemaChangeKeys.promotions(id),
    queryFn: () => listSchemaChangePromotions(id),
    enabled: !!id,
  });
  const pipelinesQuery = useQuery({
    queryKey: schemaChangeKeys.pipelines(),
    queryFn: listSchemaChangePipelines,
  });

  const refreshSet = () => {
    void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.detail(id) });
    void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.lists() });
  };
  const errorToast = (fallbackKey: string) => (err: unknown) =>
    showApiError(message, err, (e) => apiErrorMessage(e, () => t(fallbackKey)));
  // A refusal often means the page is stale (a raced promotion, a new freeze) — re-read the set.
  const refusal = (fallbackKey: string) => (err: unknown) => {
    void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.detail(id) });
    errorToast(fallbackKey)(err);
  };

  const saveStatements = useMutation({
    mutationFn: (values: StatementsForm) =>
      replaceSchemaChangeSetStatements(
        id,
        values.statements.map((s) => s.sql_text),
      ),
    onSuccess: (saved) => {
      queryClient.setQueryData(schemaChangeKeys.detail(id), saved);
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.ladder(id) });
      const warnings = saved.review_warnings ?? [];
      setProblems(findingsByStatement(warnings));
      message.success(
        warnings.length > 0
          ? t('schemaChange.detail.savedWithWarnings', { count: warnings.length })
          : t('schemaChange.detail.saved'),
      );
    },
    onError: (err) => {
      setProblems(statementProblemsFromError(err) ?? {});
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.promotions(id) });
      errorToast('schemaChange.detail.saveError')(err);
    },
  });

  const promote = useMutation({
    mutationFn: (environmentId: string) => promoteSchemaChangeSet(id, environmentId),
    onSuccess: (promotion) => {
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.lists() });
      message.success(
        t('schemaChange.detail.promoted', { environment: promotion.environment_name ?? '' }),
      );
    },
    onError: refusal('schemaChange.detail.promoteError'),
  });

  const cancelPromotion = useMutation({
    mutationFn: cancelSchemaChangePromotion,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.detail(id) });
      message.success(t('schemaChange.detail.promotionCancelled'));
    },
    onError: refusal('schemaChange.detail.cancelError'),
  });

  const updateDetails = useMutation({
    mutationFn: (input: UpdateSchemaChangeSetInput) => updateSchemaChangeSet(id, input),
    onSuccess: (saved) => {
      queryClient.setQueryData(schemaChangeKeys.detail(id), saved);
      refreshSet();
      setEditingDetails(false);
      message.success(t('schemaChange.detail.updated'));
    },
    onError: errorToast('schemaChange.detail.updateError'),
  });

  const archive = useMutation({
    mutationFn: () => updateSchemaChangeSet(id, { status: 'ARCHIVED' }),
    onSuccess: () => {
      refreshSet();
      message.success(t('schemaChange.detail.archived'));
    },
    onError: errorToast('schemaChange.detail.updateError'),
  });

  const remove = useMutation({
    mutationFn: () => deleteSchemaChangeSet(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.lists() });
      queryClient.removeQueries({ queryKey: schemaChangeKeys.detail(id) });
      message.success(t('schemaChange.detail.deleted'));
      navigate('/schema-change-sets');
    },
    onError: errorToast('schemaChange.detail.deleteError'),
  });

  const set = setQuery.data;
  const promotions = useMemo(() => promotionsQuery.data ?? [], [promotionsQuery.data]);
  const freezing = freezingPromotion(promotions);
  const archived = set?.status === 'ARCHIVED';
  // Without the promotions the freeze cannot be known, so the editor stays read-only.
  const readOnly =
    archived || freezing != null || promotionsQuery.isLoading || promotionsQuery.isError;
  const pipelineName = pipelinesQuery.data?.find((p) => p.id === set?.pipeline_id)?.name;

  if (setQuery.isLoading) {
    return (
      <div style={{ padding: 24 }} data-testid="schema-change-detail-loading">
        <Skeleton active paragraph={{ rows: 10 }} />
      </div>
    );
  }
  if (setQuery.isError || !set) {
    return (
      <Card size="small" style={{ margin: 24 }}>
        <EmptyState
          title={t('schemaChange.detail.notFound')}
          description={apiErrorMessage(setQuery.error, () => t('schemaChange.detail.notFound'))}
          action={<Button onClick={() => navigate('/schema-change-sets')}>{t('common.back')}</Button>}
        />
      </Card>
    );
  }

  const statusColor = schemaChangeSetStatusColor(set.status);
  const initialStatements: StatementsForm = {
    statements: set.statements.map((s) => ({ sql_text: s.sql_text, query_type: s.query_type })),
  };

  const promotionColumns: TableColumnsType<SchemaChangePromotion> = [
    {
      title: t('schemaChange.promotions.environment'),
      dataIndex: 'environment_name',
      render: (v: string | null | undefined) => v ?? <span className="muted">{t('schemaChange.promotions.deletedEnvironment')}</span>,
    },
    {
      title: t('schemaChange.promotions.status'),
      dataIndex: 'status',
      render: (s: SchemaChangePromotion['status']) => {
        const c = schemaChangePromotionStatusColor(s);
        return (
          <Tag style={{ color: c.fg, background: c.bg, borderColor: c.border }}>
            {schemaChangePromotionStatusLabel(t, s)}
          </Tag>
        );
      },
    },
    {
      title: t('schemaChange.promotions.submitted'),
      dataIndex: 'submitted_at',
      render: (v: string) => <span className="muted">{fmtDate(v)}</span>,
    },
    {
      title: t('schemaChange.promotions.applied'),
      dataIndex: 'applied_at',
      render: (v: string | null | undefined) => (v ? fmtDate(v) : <span className="muted">—</span>),
    },
    {
      title: t('schemaChange.promotions.group'),
      dataIndex: 'request_group_id',
      render: (v: string | null | undefined) =>
        v ? <Link to={`/request-groups/${v}`}>{t('schemaChange.ladder.openGroup')}</Link> : '—',
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={
          <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
            {set.name}
            <Tag style={{ color: statusColor.fg, background: statusColor.bg, borderColor: statusColor.border }}>
              {schemaChangeSetStatusLabel(t, set.status)}
            </Tag>
          </span>
        }
        subtitle={
          set.description ||
          t('schemaChange.detail.subtitle', { pipeline: pipelineName ?? set.pipeline_id })
        }
        breadcrumbs={[t('schemaChange.list.title'), set.name]}
        actions={
          <Space wrap>
            <Button onClick={() => navigate('/schema-change-sets')}>{t('common.back')}</Button>
            <Button
              icon={<EditOutlined />}
              onClick={() => {
                detailsForm.setFieldsValue({ name: set.name, description: set.description ?? '' });
                setEditingDetails(true);
              }}
            >
              {t('schemaChange.detail.editDetails')}
            </Button>
            {!archived && (
              <Popconfirm
                title={t('schemaChange.detail.archiveConfirm')}
                okText={t('schemaChange.detail.archive')}
                cancelText={t('common.cancel')}
                onConfirm={() => archive.mutate()}
              >
                <Button icon={<InboxOutlined />} loading={archive.isPending}>
                  {t('schemaChange.detail.archive')}
                </Button>
              </Popconfirm>
            )}
            {freezing == null && promotionsQuery.isSuccess && (
              <Popconfirm
                title={t('schemaChange.detail.deleteConfirm')}
                okText={t('common.delete')}
                okButtonProps={{ danger: true }}
                cancelText={t('common.cancel')}
                onConfirm={() => remove.mutate()}
              >
                <Button danger icon={<DeleteOutlined />} loading={remove.isPending}>
                  {t('common.delete')}
                </Button>
              </Popconfirm>
            )}
          </Space>
        }
      />
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) minmax(280px, 380px)',
          gap: 20,
          alignItems: 'start',
        }}
      >
        <Card size="small" title={t('schemaChange.statements.title')}>
          {archived && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              title={t('schemaChange.statements.archivedTitle')}
              description={t('schemaChange.statements.archivedBody')}
            />
          )}
          {!archived && promotionsQuery.isError && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              data-testid="statements-promotions-error"
              title={t('schemaChange.statements.promotionsUnknown')}
              description={apiErrorMessage(promotionsQuery.error, () =>
                t('schemaChange.promotions.loadError'),
              )}
            />
          )}
          {!archived && freezing && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              data-testid="statements-frozen"
              title={t('schemaChange.statements.frozenTitle')}
              description={t('schemaChange.statements.frozenBody', {
                environment: freezing.environment_name ?? '—',
                status: schemaChangePromotionStatusLabel(t, freezing.status),
              })}
            />
          )}
          <Form<StatementsForm>
            key={`${set.id}-${set.updated_at}`}
            form={statementsForm}
            name="schema-change-statements"
            layout="vertical"
            initialValues={initialStatements}
            onFinish={(values) => saveStatements.mutate({ statements: values.statements ?? [] })}
          >
            <StatementListEditor
              readOnly={readOnly}
              maxStatements={SCHEMA_CHANGE_MAX_STATEMENTS}
              problems={problems}
              onStructureChange={() => setProblems({})}
            />
            {!readOnly && (
              <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end' }}>
                <Button
                  type="primary"
                  htmlType="submit"
                  icon={<SaveOutlined />}
                  loading={saveStatements.isPending}
                >
                  {t('schemaChange.statements.save')}
                </Button>
              </div>
            )}
          </Form>
        </Card>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card size="small" title={t('schemaChange.ladder.title')}>
            {ladderQuery.isLoading && <Skeleton active paragraph={{ rows: 3 }} />}
            {ladderQuery.isError && (
              <Alert
                type="error"
                showIcon
                title={apiErrorMessage(ladderQuery.error, () => t('schemaChange.ladder.loadError'))}
              />
            )}
            {ladderQuery.data && ladderQuery.data.rungs.length === 0 && (
              <EmptyState size="sm" title={t('schemaChange.ladder.noEnvironments')} />
            )}
            {ladderQuery.data && ladderQuery.data.rungs.length > 0 && (
              <>
                <div className="muted" style={{ fontSize: 12, marginBottom: 10 }}>
                  {t('schemaChange.ladder.hint')}
                </div>
                <PromotionLadder
                  ladder={ladderQuery.data}
                  onPromote={(rung) => promote.mutate(rung.environment_id)}
                  onCancel={(promotionId) => cancelPromotion.mutate(promotionId)}
                  promotingEnvironmentId={promote.isPending ? promote.variables : null}
                  cancellingPromotionId={cancelPromotion.isPending ? cancelPromotion.variables : null}
                />
              </>
            )}
          </Card>
          <Card size="small" title={t('schemaChange.promotions.title')}>
            <Table<SchemaChangePromotion>
              rowKey="id"
              size="small"
              dataSource={promotions}
              columns={promotionColumns}
              loading={promotionsQuery.isLoading}
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: t('schemaChange.promotions.empty') }}
            />
          </Card>
        </div>
      </div>

      <Modal
        open={editingDetails}
        title={t('schemaChange.detail.editDetails')}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={updateDetails.isPending}
        onCancel={() => setEditingDetails(false)}
        onOk={() => detailsForm.submit()}
        destroyOnHidden
      >
        <Form<UpdateSchemaChangeSetInput>
          form={detailsForm}
          name="schema-change-details"
          layout="vertical"
          onFinish={(values) =>
            updateDetails.mutate({
              name: values.name?.trim(),
              description: values.description?.trim() ?? '',
            })
          }
        >
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
