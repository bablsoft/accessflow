import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { AutoComplete, Button, Form, Input, Select, Space, Switch, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import { requiresTargetTable } from '@/pages/lifecycle/erasureConfigForm';
import { ERASURE_CONDITION_OPERATORS, enumOptions, erasureConditionOperatorLabel } from '@/utils/enumLabels';
import type { ErasureConditionOperator } from '@/types/api';

interface ErasureConfigFormProps {
  /** The parent AntD form (owns the config field values). */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  form: FormInstance<any>;
  /** Name of the datasource-id field on the parent form (schema introspection source). */
  datasourceFieldName?: string;
  /**
   * Enforce the erasure-request shape rule (backend TARGET_TABLE_REQUIRED): target table becomes
   * required once structured conditions or a raw WHERE are set. Off by default — retention
   * policies may target by classification tag instead.
   */
  requireTargetTableWithConditions?: boolean;
}

/**
 * Shared erasure-configuration fields (AF-519): target table + columns, an AND-combined structured
 * condition builder, and a raw-WHERE escape hatch. Embedded inside a parent AntD Form and used by
 * both the admin retention-policy modal and the user "Right to Erasure" page. Structured conditions
 * and raw WHERE apply to SQL datasources only (the backend rejects them otherwise).
 */
export function ErasureConfigForm({
  form,
  datasourceFieldName = 'datasource_id',
  requireTargetTableWithConditions = false,
}: ErasureConfigFormProps) {
  const { t } = useTranslation();
  const dsId = Form.useWatch(datasourceFieldName, form) as string | undefined;

  const schemaQuery = useQuery({
    queryKey: dsId ? datasourceKeys.schema(dsId) : ['datasources', 'detail', 'none', 'schema'],
    queryFn: () => getDatasourceSchema(dsId as string),
    enabled: !!dsId,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const tableOptions = useMemo(
    () =>
      (schemaQuery.data?.schemas ?? []).flatMap((s) =>
        s.tables.map((tbl) => ({ value: `${s.name}.${tbl.name}` })),
      ),
    [schemaQuery.data],
  );
  const columnOptions = useMemo(() => {
    const names = new Set<string>();
    for (const s of schemaQuery.data?.schemas ?? []) {
      for (const tbl of s.tables) {
        for (const col of tbl.columns) names.add(col.name);
      }
    }
    return [...names].sort().map((name) => ({ value: name }));
  }, [schemaQuery.data]);

  const operatorOptions = enumOptions(ERASURE_CONDITION_OPERATORS, erasureConditionOperatorLabel, t);

  return (
    <>
      <Form.Item
        name="target_table"
        label={t('lifecycle.config.label_target_table')}
        dependencies={['conditions', 'raw_where']}
        rules={[
          { max: 255, message: t('validation.lifecycle_target_table_size') },
          ({ getFieldValue }) => ({
            required:
              requireTargetTableWithConditions &&
              requiresTargetTable({
                conditions: getFieldValue('conditions'),
                raw_where: getFieldValue('raw_where') as string | undefined,
              }),
            message: t('validation.lifecycle_erasure_target_table_required'),
          }),
        ]}
        tooltip={t('lifecycle.config.target_table_help')}
      >
        <AutoComplete
          options={tableOptions}
          filterOption={(input, option) =>
            (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
          }
          placeholder="public.users"
          allowClear
        />
      </Form.Item>

      <Form.Item name="target_columns" label={t('lifecycle.config.label_target_columns')}>
        <Select mode="tags" options={columnOptions} tokenSeparators={[',']} allowClear
          placeholder={t('lifecycle.config.target_columns_placeholder')} />
      </Form.Item>

      <Typography.Text strong>{t('lifecycle.config.conditions_title')}</Typography.Text>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        {t('lifecycle.config.conditions_help')}
      </Typography.Paragraph>

      <Form.List name="conditions">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => {
              const op = form.getFieldValue(['conditions', field.name, 'operator']) as
                | ErasureConditionOperator
                | undefined;
              return (
                <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }} wrap>
                  <Form.Item
                    name={[field.name, 'column']}
                    rules={[{ required: true, message: t('validation.lifecycle_condition_column_required') }]}
                    style={{ marginBottom: 0 }}
                  >
                    <AutoComplete
                      options={columnOptions}
                      placeholder={t('lifecycle.config.condition_column_placeholder')}
                      style={{ width: 160 }}
                      filterOption={(input, option) =>
                        (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
                      }
                    />
                  </Form.Item>
                  <Form.Item name={[field.name, 'operator']} initialValue="EQUALS" style={{ marginBottom: 0 }}>
                    <Select options={operatorOptions} style={{ width: 160 }} />
                  </Form.Item>
                  {op !== 'IS_NULL' && (
                    <Form.Item name={[field.name, 'values']} style={{ marginBottom: 0 }}>
                      <Select
                        mode="tags"
                        tokenSeparators={[',']}
                        placeholder={t('lifecycle.config.condition_values_placeholder')}
                        style={{ minWidth: 180 }}
                      />
                    </Form.Item>
                  )}
                  <Form.Item
                    name={[field.name, 'negate']}
                    valuePropName="checked"
                    initialValue={false}
                    label={t('lifecycle.config.condition_negate')}
                    style={{ marginBottom: 0 }}
                  >
                    <Switch size="small" />
                  </Form.Item>
                  <MinusCircleOutlined
                    aria-label={t('lifecycle.config.condition_remove')}
                    onClick={() => remove(field.name)}
                  />
                </Space>
              );
            })}
            <Form.Item>
              <Button type="dashed" onClick={() => add({ operator: 'EQUALS', negate: false })} block
                icon={<PlusOutlined />}>
                {t('lifecycle.config.add_condition')}
              </Button>
            </Form.Item>
          </>
        )}
      </Form.List>

      <Form.Item
        name="raw_where"
        label={t('lifecycle.config.label_raw_where')}
        tooltip={t('lifecycle.config.raw_where_help')}
        rules={[{ max: 4000, message: t('validation.lifecycle_raw_where_size') }]}
      >
        <Input.TextArea rows={2} placeholder="status = 'inactive' AND last_login < '2020-01-01'" />
      </Form.Item>
    </>
  );
}
