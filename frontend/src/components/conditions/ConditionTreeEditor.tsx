import type { ReactNode } from 'react';
import { Button, Form, Input, InputNumber, Select, Switch, TimePicker } from 'antd';
import type { Rule } from 'antd/es/form';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { BytesInput } from '@/components/common/BytesInput';
import { COMPARISON_OPERATORS, comparisonOperatorLabel, enumOptions } from '@/utils/enumLabels';
import type { ConditionRowBase } from './conditionTreeForm';

export interface ConditionSelectOption {
  value: string;
  label: string;
}

/** How one operand's value is edited. Field names are relative to the row. */
export type ConditionValueEditorSpec =
  | {
      kind: 'multi';
      field: string;
      options: ConditionSelectOption[];
      placeholder?: string;
      ariaLabel?: string;
    }
  | {
      kind: 'tags';
      field: string;
      separators: string[];
      placeholder?: string;
      ariaLabel?: string;
      /** Rejects the whole list with this message when any entry fails; null means valid. */
      validate?: (values: string[]) => string | null;
    }
  | { kind: 'bool'; field: string; label: string }
  | {
      kind: 'comparison';
      operatorField: string;
      valueField: string;
      input: 'number' | 'bytes';
      min?: number;
      max?: number;
      precision?: number;
      suffix?: ReactNode;
      ariaLabel?: string;
    }
  | { kind: 'time-range'; field: string }
  | {
      kind: 'regex';
      patternField: string;
      ignoreCaseField: string;
      maxLength: number;
      patternLabel: string;
      ignoreCaseLabel: string;
      placeholder?: string;
      validate?: (pattern: string) => string | null;
    };

export interface ConditionOperandSpec<O extends string = string> {
  value: O;
  label: string;
  editor: ConditionValueEditorSpec;
  /** The form row a freshly picked operand starts from. */
  defaultRow: () => ConditionRowBase<O>;
}

export interface ConditionTreeEditorProps<O extends string = string> {
  operands: readonly ConditionOperandSpec<O>[];
  /** The operand a new row starts with. */
  defaultOperand: O;
  listName?: string;
  matchTypeName?: string;
  /** Upper bound on the number of rows, enforced as a list validation error. */
  maxRows?: number;
}

/**
 * Guided ALL/ANY condition builder: a combinator select plus a list of (optionally negated) leaf
 * rows, each edited by its operand's value editor. Lives inside the caller's `Form`; the caller
 * converts rows to the wire tree with `rowsToTree`.
 */
export function ConditionTreeEditor<O extends string>({
  operands,
  defaultOperand,
  listName = 'conditions',
  matchTypeName = 'match_type',
  maxRows,
}: ConditionTreeEditorProps<O>) {
  const { t } = useTranslation();
  const form = Form.useFormInstance();
  const specFor = (operand: O | undefined) => operands.find((o) => o.value === operand);
  const newRow = (operand: O) => specFor(operand)?.defaultRow() ?? { operand, negate: false };

  return (
    <>
      <Form.Item name={matchTypeName} label={t('conditions.editor.label_match_type')}>
        <Select
          options={[
            { value: 'ALL', label: t('conditions.editor.match_all') },
            { value: 'ANY', label: t('conditions.editor.match_any') },
          ]}
        />
      </Form.Item>

      <Form.Item label={t('conditions.editor.label_conditions')} required>
        <Form.List
          name={listName}
          rules={[
            {
              validator: async (_rule, items: unknown[] | undefined) => {
                if (!items || items.length === 0) {
                  throw new Error(t('conditions.editor.validation_min_conditions'));
                }
                if (maxRows !== undefined && items.length > maxRows) {
                  throw new Error(t('conditions.editor.validation_max_conditions', { max: maxRows }));
                }
              },
            },
          ]}
        >
          {(fields, { add, remove }, { errors }) => (
            <>
              {fields.map(({ key, name }) => (
                <div
                  key={key}
                  style={{
                    border: '1px solid var(--border-subtle)',
                    borderRadius: 6,
                    padding: 8,
                    marginBottom: 8,
                  }}
                >
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: '1fr 90px 32px',
                      gap: 8,
                      alignItems: 'center',
                    }}
                  >
                    <Form.Item
                      name={[name, 'operand']}
                      rules={[{ required: true }]}
                      style={{ marginBottom: 0 }}
                    >
                      <Select
                        aria-label={t('conditions.editor.operand_label')}
                        options={operands.map((o) => ({ value: o.value, label: o.label }))}
                        onChange={(operand: O) => {
                          const rows = form.getFieldValue(listName) as unknown[];
                          rows[name] = newRow(operand);
                          form.setFieldsValue({ [listName]: rows });
                        }}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[name, 'negate']}
                      valuePropName="checked"
                      tooltip={t('conditions.editor.negate_hint')}
                      style={{ marginBottom: 0 }}
                    >
                      <Switch
                        checkedChildren={t('conditions.editor.not_prefix')}
                        unCheckedChildren={t('conditions.editor.is_prefix')}
                      />
                    </Form.Item>
                    <Button
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={t('conditions.editor.condition_remove')}
                      onClick={() => remove(name)}
                    />
                  </div>
                  <div style={{ marginTop: 8 }}>
                    <Form.Item
                      noStyle
                      shouldUpdate={(prev, cur) =>
                        prev[listName]?.[name]?.operand !== cur[listName]?.[name]?.operand
                      }
                    >
                      {() => {
                        const spec = specFor(form.getFieldValue([listName, name, 'operand']));
                        return spec ? <ConditionValueEditor name={name} spec={spec.editor} /> : null;
                      }}
                    </Form.Item>
                  </div>
                </div>
              ))}
              <Button
                type="dashed"
                block
                icon={<PlusOutlined />}
                onClick={() => add(newRow(defaultOperand))}
              >
                {t('conditions.editor.condition_add')}
              </Button>
              <Form.ErrorList errors={errors} />
            </>
          )}
        </Form.List>
      </Form.Item>
    </>
  );
}

const FLUSH = { marginBottom: 0 } as const;

function ConditionValueEditor({ name, spec }: { name: number; spec: ConditionValueEditorSpec }) {
  const { t } = useTranslation();
  switch (spec.kind) {
    case 'multi':
      return (
        <Form.Item name={[name, spec.field]} rules={[{ required: true }]} style={FLUSH}>
          <Select
            mode="multiple"
            aria-label={spec.ariaLabel}
            placeholder={spec.placeholder}
            options={spec.options}
          />
        </Form.Item>
      );
    case 'tags': {
      const { validate } = spec;
      const rules: Rule[] = [{ required: true }];
      if (validate) {
        rules.push({
          validator: (_rule, value: string[] | undefined) => {
            const error = validate(value ?? []);
            return error ? Promise.reject(new Error(error)) : Promise.resolve();
          },
        });
      }
      return (
        <Form.Item name={[name, spec.field]} rules={rules} style={FLUSH}>
          <Select
            mode="tags"
            tokenSeparators={spec.separators}
            aria-label={spec.ariaLabel}
            placeholder={spec.placeholder}
          />
        </Form.Item>
      );
    }
    case 'bool':
      return (
        <Form.Item name={[name, spec.field]} valuePropName="checked" label={spec.label} style={FLUSH}>
          <Switch />
        </Form.Item>
      );
    case 'comparison':
      return (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: spec.input === 'bytes' ? '1fr 2fr' : '1fr 1fr',
            gap: 8,
          }}
        >
          <Form.Item name={[name, spec.operatorField]} rules={[{ required: true }]} style={FLUSH}>
            <Select options={enumOptions(COMPARISON_OPERATORS, comparisonOperatorLabel, t)} />
          </Form.Item>
          <Form.Item
            name={[name, spec.valueField]}
            rules={[{ required: true, type: 'number', min: spec.min ?? 0, max: spec.max }]}
            style={FLUSH}
          >
            {spec.input === 'bytes' ? (
              <BytesInput aria-label={spec.ariaLabel} />
            ) : (
              <InputNumber
                min={spec.min ?? 0}
                max={spec.max}
                precision={spec.precision}
                suffix={spec.suffix}
                aria-label={spec.ariaLabel}
                style={{ width: '100%' }}
              />
            )}
          </Form.Item>
        </div>
      );
    case 'time-range':
      return (
        <Form.Item name={[name, spec.field]} rules={[{ required: true }]} style={FLUSH}>
          <TimePicker.RangePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
        </Form.Item>
      );
    case 'regex': {
      const { validate } = spec;
      const rules: Rule[] = [{ required: true, whitespace: true, max: spec.maxLength }];
      if (validate) {
        rules.push({
          validator: (_rule, value: string | undefined) => {
            const error = value ? validate(value) : null;
            return error ? Promise.reject(new Error(error)) : Promise.resolve();
          },
        });
      }
      return (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, alignItems: 'start' }}>
          <Form.Item name={[name, spec.patternField]} rules={rules} style={FLUSH}>
            <Input
              aria-label={spec.patternLabel}
              placeholder={spec.placeholder}
              maxLength={spec.maxLength}
              style={{ fontFamily: 'var(--font-mono)' }}
            />
          </Form.Item>
          <Form.Item
            name={[name, spec.ignoreCaseField]}
            valuePropName="checked"
            label={spec.ignoreCaseLabel}
            style={FLUSH}
          >
            <Switch />
          </Form.Item>
        </div>
      );
    }
  }
}
