import { Button, Card, Form, Tag } from 'antd';
import { DeleteOutlined, HolderOutlined, PlusOutlined } from '@ant-design/icons';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { statementQueryTypeLabel } from '@/utils/enumLabels';
import type { SchemaChangeSetStatement } from '@/types/api';
import { SCHEMA_CHANGE_SQL_TEXT_MAX, type StatementProblem } from '@/utils/schemaChange';

/** One row of the form's `statements` list — `query_type` is only known once saved. */
export interface StatementFormValue {
  sql_text: string;
  query_type?: SchemaChangeSetStatement['query_type'];
}

interface StatementListEditorProps {
  readOnly: boolean;
  maxStatements: number;
  problems: Record<number, StatementProblem[]>;
  /** The list order or content changed, so index-keyed problems no longer line up. */
  onStructureChange: () => void;
}

/**
 * The ordered statement list of a change set: one SQL editor per statement, drag-to-reorder,
 * and each statement's validation problems (gate refusals, BLOCK and WARN findings) under it.
 * Must render inside an AntD `Form` whose `statements` field is a `StatementFormValue[]`.
 */
export function StatementListEditor({
  readOnly,
  maxStatements,
  problems,
  onStructureChange,
}: StatementListEditorProps) {
  const { t } = useTranslation();
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  return (
    <Form.List
      name="statements"
      rules={[
        {
          validator: async (_, value: StatementFormValue[] | undefined) => {
            if ((value?.length ?? 0) > maxStatements) {
              throw new Error(t('schemaChange.statements.capRule', { max: maxStatements }));
            }
          },
        },
      ]}
    >
      {(fields, { add, remove, move }, { errors }) => {
        const onDragEnd = (event: DragEndEvent) => {
          const { active, over } = event;
          if (!over || active.id === over.id) return;
          const from = fields.findIndex((f) => f.key === active.id);
          const to = fields.findIndex((f) => f.key === over.id);
          if (from < 0 || to < 0) return;
          move(from, to);
          onStructureChange();
        };
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {fields.length === 0 && (
              <div className="muted" style={{ fontSize: 13 }}>
                {t('schemaChange.statements.empty')}
              </div>
            )}
            <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
              <SortableContext items={fields.map((f) => f.key)} strategy={verticalListSortingStrategy}>
                {fields.map((field, index) => (
                  <SortableStatement key={field.key} id={field.key} index={index} readOnly={readOnly}>
                    {(handle) => (
                      <Card
                        size="small"
                        title={
                          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            {handle}
                            <span className="mono muted">#{index + 1}</span>
                            <Form.Item noStyle shouldUpdate>
                              {({ getFieldValue }) => {
                                const queryType = getFieldValue(['statements', field.name, 'query_type']) as
                                  | StatementFormValue['query_type']
                                  | undefined;
                                return queryType ? <Tag>{statementQueryTypeLabel(t, queryType)}</Tag> : null;
                              }}
                            </Form.Item>
                          </div>
                        }
                        extra={
                          !readOnly && (
                            <Button
                              type="text"
                              danger
                              size="small"
                              icon={<DeleteOutlined />}
                              aria-label={t('schemaChange.statements.remove', { index: index + 1 })}
                              onClick={() => {
                                remove(field.name);
                                onStructureChange();
                              }}
                            />
                          )
                        }
                      >
                        <Form.Item
                          name={[field.name, 'sql_text']}
                          label={t('schemaChange.statements.sqlLabel', { index: index + 1 })}
                          rules={[
                            {
                              required: true,
                              whitespace: true,
                              message: t('schemaChange.statements.sqlRequired'),
                            },
                            {
                              max: SCHEMA_CHANGE_SQL_TEXT_MAX,
                              message: t('schemaChange.statements.sqlMax', {
                                max: SCHEMA_CHANGE_SQL_TEXT_MAX,
                              }),
                            },
                          ]}
                          style={{ marginBottom: 0 }}
                        >
                          <SqlEditorControl readOnly={readOnly} />
                        </Form.Item>
                        <StatementProblems problems={problems[index] ?? []} index={index} />
                      </Card>
                    )}
                  </SortableStatement>
                ))}
              </SortableContext>
            </DndContext>
            <Form.ErrorList errors={errors} />
            {!readOnly && (
              <Button
                icon={<PlusOutlined />}
                disabled={fields.length >= maxStatements}
                onClick={() => {
                  add({ sql_text: '' });
                  onStructureChange();
                }}
                style={{ alignSelf: 'flex-start' }}
              >
                {t('schemaChange.statements.add')}
              </Button>
            )}
            {!readOnly && fields.length >= maxStatements && (
              <div className="muted" style={{ fontSize: 12 }}>
                {t('schemaChange.statements.capReached', { max: maxStatements })}
              </div>
            )}
          </div>
        );
      }}
    </Form.List>
  );
}

function SortableStatement({
  id,
  index,
  readOnly,
  children,
}: {
  id: number;
  index: number;
  readOnly: boolean;
  children: (handle: ReactNode) => ReactNode;
}) {
  const { t } = useTranslation();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled: readOnly,
  });
  const handle = readOnly ? null : (
    <button
      type="button"
      className="af-icon-btn"
      aria-label={t('schemaChange.statements.dragHandle', { index: index + 1 })}
      style={{ cursor: 'grab', touchAction: 'none' }}
      {...attributes}
      {...listeners}
    >
      <HolderOutlined />
    </button>
  );
  return (
    <div
      ref={setNodeRef}
      data-testid={`statement-${index}`}
      style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.6 : 1 }}
    >
      {children(handle)}
    </div>
  );
}

/** Adapts the SQL editor to AntD's injected `value` / `onChange` control props. */
function SqlEditorControl({
  value,
  onChange,
  readOnly,
}: {
  value?: string;
  onChange?: (next: string) => void;
  readOnly: boolean;
}) {
  return (
    <SqlEditor value={value ?? ''} onChange={(next) => onChange?.(next)} readOnly={readOnly} height={140} />
  );
}

function StatementProblems({ problems, index }: { problems: StatementProblem[]; index: number }) {
  const { t } = useTranslation();
  if (problems.length === 0) return null;
  return (
    <ul
      aria-label={t('schemaChange.statements.problemsLabel', { index: index + 1 })}
      data-testid={`statement-problems-${index}`}
      style={{ listStyle: 'none', margin: '8px 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 4 }}
    >
      {problems.map((p, i) => (
        <li
          key={`${p.severity}-${p.ruleId ?? ''}-${p.datasourceId ?? ''}-${i}`}
          style={{
            fontSize: 12,
            color: p.severity === 'WARN' ? 'var(--status-warn)' : 'var(--risk-crit)',
          }}
        >
          <Tag
            style={{
              color: p.severity === 'WARN' ? 'var(--status-warn)' : 'var(--risk-crit)',
              background: p.severity === 'WARN' ? 'var(--status-warn-bg)' : 'var(--risk-crit-bg)',
              borderColor: p.severity === 'WARN' ? 'var(--status-warn-border)' : 'var(--risk-crit-border)',
            }}
          >
            {t(`schemaChange.statements.severity.${p.severity}`)}
          </Tag>
          {p.ruleId && <span className="mono" style={{ marginRight: 6 }}>{p.ruleId}</span>}
          {p.message}
        </li>
      ))}
    </ul>
  );
}
