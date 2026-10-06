import { App, Button, Skeleton, Switch, Table } from 'antd';
import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  useDeleteSqlReviewCustomRule,
  useSqlReviewCustomRules,
  useUpdateSqlReviewCustomRule,
} from '@/hooks/useSqlReviewCustomRules';
import type { SqlReviewCustomRule } from '@/types/api';
import { sqlReviewRuleErrorMessage } from '@/utils/apiErrors';
import { fmtDate } from '@/utils/dateFormat';
import { sqlReviewRuleCategoryLabel, sqlReviewSeverityLabel } from '@/utils/enumLabels';
import { sqlReviewSeverityColor } from '@/utils/riskColors';
import { showApiError } from '@/utils/showApiError';
import { toggleEnabledRequest } from './sqlReviewCustomRuleForm';

interface SqlReviewCustomRulesTabProps {
  onEdit: (rule: SqlReviewCustomRule) => void;
}

/** The `?tab=rules` panel of `/admin/sql-review` (#1011): the organization's custom rules. */
export function SqlReviewCustomRulesTab({ onEdit }: SqlReviewCustomRulesTabProps) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const rulesQuery = useSqlReviewCustomRules();
  const updateMutation = useUpdateSqlReviewCustomRule();
  const deleteMutation = useDeleteSqlReviewCustomRule();

  const toggleEnabled = (rule: SqlReviewCustomRule, enabled: boolean) =>
    updateMutation.mutate(
      { id: rule.id, payload: toggleEnabledRequest(rule, enabled) },
      { onError: (err) => showApiError(message, err, sqlReviewRuleErrorMessage) },
    );

  const onDelete = (rule: SqlReviewCustomRule) =>
    modal.confirm({
      title: t('admin.sql_review.custom_rules.delete_confirm_title'),
      content: t('admin.sql_review.custom_rules.delete_confirm_body', { name: rule.name }),
      okType: 'danger',
      okText: t('common.delete'),
      cancelText: t('common.cancel'),
      onOk: () =>
        deleteMutation.mutateAsync(rule.id).then(
          () => {
            message.success(t('admin.sql_review.custom_rules.delete_success'));
          },
          (err: unknown) => showApiError(message, err, sqlReviewRuleErrorMessage),
        ),
    });

  if (rulesQuery.isLoading) {
    return <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />;
  }
  if (rulesQuery.isError) {
    return (
      <EmptyState
        title={t('admin.sql_review.custom_rules.load_error')}
        description={sqlReviewRuleErrorMessage(rulesQuery.error)}
      />
    );
  }
  const rules = rulesQuery.data ?? [];
  if (rules.length === 0) {
    return (
      <EmptyState
        title={t('admin.sql_review.custom_rules.empty_title')}
        description={t('admin.sql_review.custom_rules.empty')}
      />
    );
  }

  return (
    <Table<SqlReviewCustomRule>
      rowKey="id"
      size="middle"
      dataSource={rules}
      scroll={{ x: 'max-content' }}
      pagination={false}
      columns={[
        {
          title: t('admin.sql_review.custom_rules.col_name'),
          dataIndex: 'name',
          render: (v: string, rule) => (
            <div>
              <div style={{ fontWeight: 500 }}>{v}</div>
              {rule.description && (
                <div className="muted" style={{ fontSize: 12 }}>
                  {rule.description}
                </div>
              )}
            </div>
          ),
        },
        {
          title: t('admin.sql_review.custom_rules.col_rule_id'),
          dataIndex: 'rule_id',
          render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
        },
        {
          title: t('admin.sql_review.custom_rules.col_category'),
          dataIndex: 'category',
          width: 160,
          render: (_v, rule) => (
            <span className="muted">{sqlReviewRuleCategoryLabel(t, rule.category)}</span>
          ),
        },
        {
          title: t('admin.sql_review.custom_rules.col_default_severity'),
          dataIndex: 'default_severity',
          width: 140,
          render: (_v, rule) => {
            const label = sqlReviewSeverityLabel(t, rule.default_severity);
            if (rule.default_severity === 'OFF') {
              return <span className="muted">{label}</span>;
            }
            const colors = sqlReviewSeverityColor(rule.default_severity);
            return (
              <Pill fg={colors.fg} bg={colors.bg} border={colors.border} size="sm">
                {label}
              </Pill>
            );
          },
        },
        {
          title: t('admin.sql_review.custom_rules.col_enabled'),
          dataIndex: 'enabled',
          width: 90,
          render: (v: boolean, rule) => (
            <Switch
              size="small"
              checked={v}
              aria-label={t('admin.sql_review.custom_rules.enabled_for_rule', { name: rule.name })}
              disabled={updateMutation.isPending}
              onChange={(checked) => toggleEnabled(rule, checked)}
            />
          ),
        },
        {
          title: t('admin.sql_review.custom_rules.col_updated_at'),
          dataIndex: 'updated_at',
          width: 180,
          render: (v: string) => <span className="muted">{fmtDate(v)}</span>,
        },
        {
          title: t('admin.sql_review.col_actions'),
          width: 110,
          render: (_v, rule) => (
            <div style={{ display: 'flex', gap: 4 }}>
              <Button
                size="small"
                type="text"
                icon={<EditOutlined />}
                aria-label={t('admin.sql_review.custom_rules.edit_rule', { name: rule.name })}
                onClick={() => onEdit(rule)}
              />
              <Button
                size="small"
                type="text"
                icon={<DeleteOutlined />}
                aria-label={t('admin.sql_review.custom_rules.delete_rule', { name: rule.name })}
                onClick={() => onDelete(rule)}
              />
            </div>
          ),
        },
      ]}
    />
  );
}
