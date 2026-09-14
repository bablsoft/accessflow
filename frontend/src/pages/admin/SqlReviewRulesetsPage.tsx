import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Skeleton,
  Switch,
  Table,
  Tooltip,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  createSqlReviewRuleset,
  deleteSqlReviewRuleset,
  getSqlReviewRules,
  listSqlReviewRulesets,
  sqlReviewKeys,
  updateSqlReviewRuleset,
} from '@/api/sqlReview';
import type {
  SqlReviewRule,
  SqlReviewRuleset,
  SqlReviewRulesetWriteRequest,
} from '@/types/api';
import { sqlReviewRulesetErrorMessage } from '@/utils/apiErrors';
import {
  SQL_REVIEW_SEVERITIES,
  enumOptions,
  sqlReviewRuleCategoryLabel,
  sqlReviewSeverityLabel,
} from '@/utils/enumLabels';
import { sqlReviewSeverityColor } from '@/utils/riskColors';
import { showApiError } from '@/utils/showApiError';
import {
  environmentOptions,
  rulesSummary,
  rulesetEnvironmentLabel,
  toFormValues,
  toWriteRequest,
  toggleEnabledRequest,
  validateParam,
  type SqlReviewRulesetFormValues,
} from './sqlReviewForm';

/**
 * `/admin/sql-review` (#865): the organization's deterministic SQL review rulesets — one per
 * environment plus an optional organization default — and, per ruleset, the severity every
 * catalog rule runs at. The rule rows come from `GET /sql-review/rules`, never a client-side list,
 * so a rule added on the backend appears here without a frontend change.
 */
export function SqlReviewRulesetsPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<SqlReviewRuleset | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<SqlReviewRulesetFormValues>();

  const rulesetsQuery = useQuery({
    queryKey: sqlReviewKeys.rulesets(),
    queryFn: listSqlReviewRulesets,
  });
  const rulesQuery = useQuery({
    queryKey: sqlReviewKeys.rules(),
    queryFn: getSqlReviewRules,
    // The built-in catalog only changes with a release.
    staleTime: 5 * 60_000,
  });
  const catalog = useMemo(() => rulesQuery.data ?? [], [rulesQuery.data]);
  const rulesets = useMemo(() => rulesetsQuery.data ?? [], [rulesetsQuery.data]);

  const isOpen = creating || editing !== null;

  useEffect(() => {
    if (!isOpen) return;
    form.resetFields();
    form.setFieldsValue(toFormValues(editing, catalog));
  }, [isOpen, editing, catalog, form]);

  const closeModal = () => {
    setCreating(false);
    setEditing(null);
  };

  const invalidate = () => queryClient.invalidateQueries({ queryKey: sqlReviewKeys.rulesets() });

  const createMutation = useMutation({
    mutationFn: (payload: SqlReviewRulesetWriteRequest) => createSqlReviewRuleset(payload),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.sql_review.create_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, sqlReviewRulesetErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: SqlReviewRulesetWriteRequest }) =>
      updateSqlReviewRuleset(id, payload),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.sql_review.update_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, sqlReviewRulesetErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteSqlReviewRuleset(id),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.sql_review.delete_success'));
    },
    onError: (err) => showApiError(message, err, sqlReviewRulesetErrorMessage),
  });

  const onFinish = (values: SqlReviewRulesetFormValues) => {
    const payload = toWriteRequest(values, catalog);
    if (editing) {
      updateMutation.mutate({ id: editing.id, payload });
    } else {
      createMutation.mutate(payload);
    }
  };

  const onDelete = (ruleset: SqlReviewRuleset) =>
    modal.confirm({
      title: t('admin.sql_review.delete_confirm_title'),
      content: t('admin.sql_review.delete_confirm_body', { name: ruleset.name }),
      okType: 'danger',
      okText: t('common.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(ruleset.id),
    });

  // PUT is a full replace, so the list-level toggle resends the stored rules verbatim.
  const toggleEnabled = (ruleset: SqlReviewRuleset, enabled: boolean) =>
    updateMutation.mutate({ id: ruleset.id, payload: toggleEnabledRequest(ruleset, enabled) });

  const severityOptions = enumOptions(SQL_REVIEW_SEVERITIES, sqlReviewSeverityLabel, t);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.sql_review.title')}
        subtitle={t('admin.sql_review.subtitle')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => rulesetsQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null);
                setCreating(true);
              }}
            >
              {t('admin.sql_review.add_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {rulesetsQuery.isLoading || rulesQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : rulesetsQuery.isError ? (
          <EmptyState
            title={t('admin.sql_review.load_error')}
            description={sqlReviewRulesetErrorMessage(rulesetsQuery.error)}
          />
        ) : rulesQuery.isError ? (
          <EmptyState
            title={t('admin.sql_review.rules_load_error')}
            description={sqlReviewRulesetErrorMessage(rulesQuery.error)}
          />
        ) : rulesets.length === 0 ? (
          <EmptyState title={t('admin.sql_review.title')} description={t('admin.sql_review.empty')} />
        ) : (
          <Table<SqlReviewRuleset>
            rowKey="id"
            size="middle"
            dataSource={rulesets}
            scroll={{ x: 'max-content' }}
            pagination={false}
            columns={[
              {
                title: t('admin.sql_review.col_name'),
                dataIndex: 'name',
                render: (v: string, ruleset) => (
                  <div>
                    <div style={{ fontWeight: 500 }}>{v}</div>
                    {ruleset.description && (
                      <div className="muted" style={{ fontSize: 12 }}>
                        {ruleset.description}
                      </div>
                    )}
                  </div>
                ),
              },
              {
                title: t('admin.sql_review.col_environment'),
                dataIndex: 'environment',
                width: 180,
                render: (_v, ruleset) => (
                  <Pill
                    fg="var(--fg)"
                    bg="var(--status-neutral-bg)"
                    border="var(--status-neutral-border)"
                    size="sm"
                  >
                    {rulesetEnvironmentLabel(t, ruleset)}
                  </Pill>
                ),
              },
              {
                title: t('admin.sql_review.col_rules'),
                width: 220,
                render: (_v, ruleset) => {
                  const summary = rulesSummary(ruleset, catalog);
                  return (
                    <span className="muted" data-testid="sql-review-rules-summary">
                      {t('admin.sql_review.rules_summary', { ...summary })}
                    </span>
                  );
                },
              },
              {
                title: t('admin.sql_review.col_enabled'),
                dataIndex: 'enabled',
                width: 90,
                render: (v: boolean, ruleset) => (
                  <Switch
                    size="small"
                    checked={v}
                    aria-label={t('admin.sql_review.col_enabled')}
                    disabled={updateMutation.isPending}
                    onChange={(checked) => toggleEnabled(ruleset, checked)}
                  />
                ),
              },
              {
                title: t('admin.sql_review.col_actions'),
                width: 110,
                render: (_v, ruleset) => (
                  <div style={{ display: 'flex', gap: 4 }}>
                    <Button
                      size="small"
                      type="text"
                      icon={<EditOutlined />}
                      aria-label={t('common.edit')}
                      onClick={() => {
                        setCreating(false);
                        setEditing(ruleset);
                      }}
                    />
                    <Button
                      size="small"
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={t('common.delete')}
                      onClick={() => onDelete(ruleset)}
                    />
                  </div>
                ),
              },
            ]}
          />
        )}
      </div>

      <Modal
        open={isOpen}
        title={
          editing
            ? t('admin.sql_review.edit_modal_title', { name: editing.name })
            : t('admin.sql_review.create_modal_title')
        }
        onCancel={closeModal}
        onOk={() => form.submit()}
        okText={editing ? t('admin.sql_review.save_update') : t('admin.sql_review.save_create')}
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
        destroyOnHidden
        width={860}
      >
        <Form<SqlReviewRulesetFormValues>
          form={form}
          name="sql-review-ruleset"
          layout="vertical"
          onFinish={onFinish}
        >
          {/* name ↔ @NotBlank @Size(max = 255); description ↔ @Size(max = 2000) */}
          <Form.Item
            name="name"
            label={t('admin.sql_review.label_name')}
            rules={[{ required: true, max: 255, whitespace: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('admin.sql_review.label_description')}
            rules={[{ max: 2000 }]}
          >
            <Input.TextArea rows={2} />
          </Form.Item>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 16 }}>
            <Form.Item
              name="environment"
              label={t('admin.sql_review.label_environment')}
              extra={t('admin.sql_review.environment_help')}
            >
              <Select options={environmentOptions(t)} />
            </Form.Item>
            <Form.Item
              name="enabled"
              label={t('admin.sql_review.label_enabled')}
              valuePropName="checked"
              extra={t('admin.sql_review.enabled_help')}
            >
              <Switch />
            </Form.Item>
          </div>

          <div style={{ fontWeight: 600, marginBottom: 4 }}>{t('admin.sql_review.rules_heading')}</div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
            {t('admin.sql_review.rules_hint')}
          </div>
          <Table<SqlReviewRule>
            rowKey="rule_id"
            size="small"
            dataSource={catalog}
            pagination={false}
            columns={[
              {
                title: t('admin.sql_review.rules_col_rule'),
                render: (_v, rule) => (
                  <div>
                    <div style={{ fontWeight: 500 }}>{rule.name}</div>
                    <div className="muted" style={{ fontSize: 12 }}>
                      {rule.description}
                    </div>
                  </div>
                ),
              },
              {
                title: t('admin.sql_review.rules_col_category'),
                width: 150,
                render: (_v, rule) => (
                  <span className="muted" style={{ fontSize: 12 }}>
                    {sqlReviewRuleCategoryLabel(t, rule.category)}
                  </span>
                ),
              },
              {
                title: t('admin.sql_review.rules_col_severity'),
                width: 170,
                render: (_v, rule) => (
                  <Form.Item
                    name={['rules', rule.rule_id, 'severity']}
                    style={{ marginBottom: 0 }}
                    extra={
                      <Tooltip title={t('admin.sql_review.default_severity_hint')}>
                        <span style={{ fontSize: 11 }}>
                          {t('admin.sql_review.default_severity', {
                            severity: sqlReviewSeverityLabel(t, rule.default_severity),
                          })}
                        </span>
                      </Tooltip>
                    }
                  >
                    {/* A Select, not Segmented: Playwright cannot drive Segmented's hidden radio. */}
                    <Select
                      size="small"
                      aria-label={t('admin.sql_review.severity_for_rule', { name: rule.name })}
                      options={severityOptions}
                      optionRender={(option) => {
                        const value = option.value as (typeof SQL_REVIEW_SEVERITIES)[number];
                        const colors = value === 'OFF' ? null : sqlReviewSeverityColor(value);
                        return (
                          <span style={{ color: colors?.fg }}>{option.label}</span>
                        );
                      }}
                    />
                  </Form.Item>
                ),
              },
              {
                title: t('admin.sql_review.rules_col_params'),
                width: 260,
                render: (_v, rule) =>
                  rule.params.length === 0 ? (
                    <span className="muted" style={{ fontSize: 12 }}>
                      {t('admin.sql_review.params_none')}
                    </span>
                  ) : (
                    rule.params.map((param) => (
                      <Form.Item
                        key={param.key}
                        name={['rules', rule.rule_id, 'params', param.key]}
                        style={{ marginBottom: 0 }}
                        // Re-validate when the severity moves the row in or out of "stored".
                        dependencies={[['rules', rule.rule_id, 'severity']]}
                        rules={[
                          {
                            validator: (_rule, values: string[] | undefined) => {
                              const row = form.getFieldValue(['rules', rule.rule_id]) as
                                | SqlReviewRulesetFormValues['rules'][string]
                                | undefined;
                              const error = validateParam(rule, param, {
                                severity: row?.severity ?? rule.default_severity,
                                params: { ...(row?.params ?? {}), [param.key]: values ?? [] },
                              });
                              if (!error) return Promise.resolve();
                              return Promise.reject(
                                new Error(
                                  error.kind === 'required'
                                    ? t('admin.sql_review.param_required')
                                    : t('admin.sql_review.param_invalid', { value: error.value }),
                                ),
                              );
                            },
                          },
                        ]}
                      >
                        <Select
                          mode="tags"
                          size="small"
                          tokenSeparators={[',', ' ']}
                          open={false}
                          aria-label={t('admin.sql_review.param_for_rule', {
                            key: param.key,
                            name: rule.name,
                          })}
                          placeholder={
                            param.defaults.length > 0
                              ? t('admin.sql_review.param_placeholder_defaults', {
                                  values: param.defaults.join(', '),
                                })
                              : t('admin.sql_review.param_placeholder', { key: param.key })
                          }
                        />
                      </Form.Item>
                    ))
                  ),
              },
            ]}
          />
        </Form>
      </Modal>
    </div>
  );
}

export default SqlReviewRulesetsPage;
