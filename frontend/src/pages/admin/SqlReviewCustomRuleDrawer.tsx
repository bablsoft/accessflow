import { useMemo } from 'react';
import { Alert, App, Button, Drawer, Form, Input, Select, Switch } from 'antd';
import { ExperimentOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ConditionTreeEditor } from '@/components/conditions/ConditionTreeEditor';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { SqlReviewFindingList } from '@/components/review/SqlReviewFindingList';
import {
  useCreateSqlReviewCustomRule,
  useTestSqlReviewCustomRule,
  useUpdateSqlReviewCustomRule,
} from '@/hooks/useSqlReviewCustomRules';
import type { SqlReviewCustomRule, SqlReviewTestDialect } from '@/types/api';
import { sqlReviewRuleErrorMessage } from '@/utils/apiErrors';
import {
  SQL_REVIEW_RULE_CATEGORIES,
  SQL_REVIEW_SEVERITIES,
  SQL_REVIEW_TEST_DIALECTS,
  dbTypeLabel,
  enumOptions,
  sqlReviewRuleCategoryLabel,
  sqlReviewSeverityLabel,
} from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import {
  CUSTOM_RULE_PREFIX,
  MAX_CONDITION_ROWS,
  MAX_MESSAGE_LENGTH,
  MAX_TEST_SQL_LENGTH,
  MESSAGE_PLACEHOLDERS,
  SLUG_PATTERN,
  toFormValues,
  toWriteRequest,
  type SqlReviewCustomRuleFormValues,
} from './sqlReviewCustomRuleForm';
import { sqlRuleOperandSpecs } from './sqlRuleOperands';

interface TestFormValues {
  sql: string;
  dialect: SqlReviewTestDialect;
}

interface SqlReviewCustomRuleDrawerProps {
  open: boolean;
  /** The rule being edited; null creates one. */
  rule: SqlReviewCustomRule | null;
  onClose: () => void;
}

/**
 * Create / edit drawer for an organization custom SQL review rule (#1011), with a "Test against
 * SQL" panel that evaluates the unsaved draft through `POST /admin/sql-review-rules/test`.
 */
export function SqlReviewCustomRuleDrawer({ open, rule, onClose }: SqlReviewCustomRuleDrawerProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<SqlReviewCustomRuleFormValues>();
  const [testForm] = Form.useForm<TestFormValues>();
  const createMutation = useCreateSqlReviewCustomRule();
  const updateMutation = useUpdateSqlReviewCustomRule();
  const testMutation = useTestSqlReviewCustomRule();
  const operands = useMemo(() => sqlRuleOperandSpecs(t), [t]);
  // The drawer content is destroyed on close and both Forms clear their store with it
  // (`clearOnDestroy`) — the `useForm` instances outlive the content, so without it the next open
  // would merge the previous rule's values over these.
  const initial = useMemo(() => toFormValues(rule), [rule]);
  const saving = createMutation.isPending || updateMutation.isPending;

  const close = () => {
    testMutation.reset();
    onClose();
  };

  const onFinish = (values: SqlReviewCustomRuleFormValues) => {
    const payload = toWriteRequest(values);
    const options = {
      onSuccess: () => {
        message.success(
          t(rule ? 'admin.sql_review.custom_rules.update_success' : 'admin.sql_review.custom_rules.create_success'),
        );
        close();
      },
      onError: (err: unknown) => showApiError(message, err, sqlReviewRuleErrorMessage),
    };
    if (rule) {
      updateMutation.mutate({ id: rule.id, payload }, options);
    } else {
      createMutation.mutate(payload, options);
    }
  };

  const runTest = async () => {
    try {
      await form.validateFields();
      await testForm.validateFields();
    } catch {
      // Field errors render inline.
      return;
    }
    const values = form.getFieldsValue(true) as SqlReviewCustomRuleFormValues;
    const test = testForm.getFieldsValue(true) as TestFormValues;
    testMutation.mutate({ rule: toWriteRequest(values), sql: test.sql, dialect: test.dialect });
  };

  const findings = testMutation.data?.findings;
  const testDialect = Form.useWatch('dialect', testForm);

  return (
    <Drawer
      open={open}
      onClose={close}
      size="min(820px, 100vw)"
      destroyOnHidden
      title={
        rule
          ? t('admin.sql_review.custom_rules.edit_title', { name: rule.name })
          : t('admin.sql_review.custom_rules.create_title')
      }
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={close}>{t('common.cancel')}</Button>
          <Button type="primary" loading={saving} onClick={() => form.submit()}>
            {rule
              ? t('admin.sql_review.custom_rules.save_update')
              : t('admin.sql_review.custom_rules.save_create')}
          </Button>
        </div>
      }
    >
      {!initial.supported && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={t('admin.sql_review.custom_rules.condition_advanced_warning')}
        />
      )}
      <Form<SqlReviewCustomRuleFormValues>
        form={form}
        name="sql-review-custom-rule"
        layout="vertical"
        clearOnDestroy
        initialValues={initial.values}
        onFinish={onFinish}
      >
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          {/* slug ↔ rule_id @Pattern ^custom_[a-z][a-z0-9_]{2,60}$ — immutable after create */}
          <Form.Item
            name="slug"
            label={t('admin.sql_review.custom_rules.label_slug')}
            extra={t('admin.sql_review.custom_rules.slug_help')}
            rules={[
              { required: true, whitespace: true },
              { pattern: SLUG_PATTERN, message: t('admin.sql_review.custom_rules.slug_invalid') },
            ]}
          >
            <Input prefix={<span className="muted">{CUSTOM_RULE_PREFIX}</span>} disabled={rule !== null} />
          </Form.Item>
          {/* name ↔ @NotBlank @Size(max = 255) */}
          <Form.Item
            name="name"
            label={t('admin.sql_review.custom_rules.label_name')}
            rules={[{ required: true, max: 255, whitespace: true }]}
          >
            <Input />
          </Form.Item>
        </div>
        {/* description ↔ @Size(max = 2000) */}
        <Form.Item
          name="description"
          label={t('admin.sql_review.custom_rules.label_description')}
          rules={[{ max: 2000 }]}
        >
          <Input.TextArea rows={2} />
        </Form.Item>
        {/* message ↔ @NotBlank @Size(max = 500) */}
        <Form.Item
          name="message"
          label={t('admin.sql_review.custom_rules.label_message')}
          extra={t('admin.sql_review.custom_rules.message_help', {
            placeholders: MESSAGE_PLACEHOLDERS.join(', '),
          })}
          rules={[{ required: true, max: MAX_MESSAGE_LENGTH, whitespace: true }]}
        >
          <Input.TextArea rows={2} maxLength={MAX_MESSAGE_LENGTH} />
        </Form.Item>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 12 }}>
          <Form.Item
            name="category"
            label={t('admin.sql_review.custom_rules.label_category')}
            rules={[{ required: true }]}
          >
            <Select options={enumOptions(SQL_REVIEW_RULE_CATEGORIES, sqlReviewRuleCategoryLabel, t)} />
          </Form.Item>
          <Form.Item
            name="default_severity"
            label={t('admin.sql_review.custom_rules.label_default_severity')}
            extra={t('admin.sql_review.custom_rules.default_severity_help')}
            rules={[{ required: true }]}
          >
            <Select options={enumOptions(SQL_REVIEW_SEVERITIES, sqlReviewSeverityLabel, t)} />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('admin.sql_review.custom_rules.label_enabled')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </div>

        <ConditionTreeEditor
          operands={operands}
          defaultOperand="query_type"
          maxRows={MAX_CONDITION_ROWS}
        />
      </Form>

      <section
        aria-label={t('admin.sql_review.custom_rules.test_heading')}
        style={{ borderTop: '1px solid var(--border-subtle)', paddingTop: 16, marginTop: 8 }}
      >
        <div style={{ fontWeight: 600, marginBottom: 4 }}>
          {t('admin.sql_review.custom_rules.test_heading')}
        </div>
        <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
          {t('admin.sql_review.custom_rules.test_hint')}
        </div>
        <Form<TestFormValues>
          form={testForm}
          name="sql-review-rule-test"
          layout="vertical"
          clearOnDestroy
          initialValues={{ sql: '', dialect: 'POSTGRESQL' }}
        >
          <Form.Item
            name="dialect"
            label={t('admin.sql_review.custom_rules.label_dialect')}
            style={{ maxWidth: 260 }}
          >
            <Select
              options={SQL_REVIEW_TEST_DIALECTS.map((d) => ({ value: d, label: dbTypeLabel(t, d) }))}
            />
          </Form.Item>
          {/* sql ↔ @NotBlank @Size(max = 100_000) */}
          <Form.Item
            name="sql"
            label={t('admin.sql_review.custom_rules.label_test_sql')}
            rules={[{ required: true, whitespace: true, max: MAX_TEST_SQL_LENGTH }]}
          >
            <TestSqlEditor dbType={testDialect ?? 'POSTGRESQL'} />
          </Form.Item>
        </Form>
        <Button
          icon={<ExperimentOutlined />}
          loading={testMutation.isPending}
          onClick={() => void runTest()}
        >
          {t('admin.sql_review.custom_rules.run_test')}
        </Button>
        <div style={{ marginTop: 12 }} data-testid="sql-review-rule-test-result">
          {testMutation.isError && (
            <Alert type="error" showIcon title={sqlReviewRuleErrorMessage(testMutation.error)} />
          )}
          {findings &&
            (findings.length > 0 ? (
              <SqlReviewFindingList findings={findings} density="compact" />
            ) : (
              <div className="muted">{t('admin.sql_review.custom_rules.test_no_findings')}</div>
            ))}
        </div>
      </section>
    </Drawer>
  );
}

/** Form.Item injects value / onChange; the dialect drives the editor's SQL highlighting. */
function TestSqlEditor({
  value,
  onChange,
  dbType,
}: {
  value?: string;
  onChange?: (next: string) => void;
  dbType: SqlReviewTestDialect;
}) {
  return (
    <SqlEditor value={value ?? ''} onChange={(next) => onChange?.(next)} dbType={dbType} height={140} />
  );
}
