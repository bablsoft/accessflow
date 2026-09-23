import { useMemo, useState } from 'react';
import { AutoComplete, Button, Form, Select, Skeleton, Space, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { skipToken, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import {
  effectiveAccessKeys,
  getEffectiveAccess,
  type EffectiveAccessFilters,
} from '@/api/accessSimulations';
import { hasQueryAdminBypass } from './effectiveAccess';
import { Avatar } from '@/components/common/Avatar';
import { SimulationDatasourceSelect } from '@/components/policies/SimulationDatasourceSelect';
import { useCanListAllDatasources } from '@/components/policies/useSimulationDatasources';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import { apiErrorMessage } from '@/utils/apiErrors';
import { fmtDate } from '@/utils/dateFormat';
import {
  STATEMENT_CAPABILITIES,
  accessSourceKindLabel,
  effectiveAccessTableScopeLabel,
  enumOptions,
  statementCapabilityLabel,
} from '@/utils/enumLabels';
import { decisionStepOutcomeColor } from '@/utils/statusColors';
import type {
  EffectiveAccessRow,
  EffectiveAccessSource,
  StatementCapability,
} from '@/types/api';

const PAGE_SIZE = 20;

interface LookupValues {
  datasource_id?: string;
  table?: string;
  capability: StatementCapability;
}

type Lookup = Omit<EffectiveAccessFilters, 'page' | 'size'>;

function Flag({ on }: { on: boolean }) {
  const { t } = useTranslation();
  const color = decisionStepOutcomeColor(on ? 'MATCH' : 'NO_MATCH');
  return (
    <Pill fg={color.fg} bg={color.bg} border={color.border} size="sm">
      {on ? t('common.yes') : t('common.no')}
    </Pill>
  );
}

function SourceLine({ source }: { source: EffectiveAccessSource }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (source.group_name != null) parts.push(t('decisionTrace.via_group', { group: source.group_name }));
  if (source.covering_allow_list_entry != null) {
    parts.push(t('access.effective.allow_list_entry', { entry: source.covering_allow_list_entry }));
  }
  if (source.kind !== 'QUERY_ADMIN_BYPASS') {
    parts.push(
      source.expires_at != null
        ? t('decisionTrace.expires_at', { date: fmtDate(source.expires_at) })
        : t('decisionTrace.never_expires'),
    );
  }
  if (!source.grants_capability) parts.push(t('access.effective.not_this_capability'));
  return (
    <div style={{ fontSize: 12 }}>
      <span>{accessSourceKindLabel(t, source.kind)}</span>
      {parts.length > 0 && (
        <span className="muted" style={{ marginLeft: 6, fontSize: 11 }}>
          {parts.join(' · ')}
        </span>
      )}
    </div>
  );
}

/**
 * The reverse access index (#859 endpoint, #1066 UI): who can perform a capability on one table,
 * and through which grants. Break-glass is its own column — "can write anyway" is not "can write".
 */
export function EffectiveAccessPanel() {
  const { t } = useTranslation();
  const [form] = Form.useForm<LookupValues>();
  const datasourceId = Form.useWatch('datasource_id', form);
  const [lookup, setLookup] = useState<Lookup | null>(null);
  const [page, setPage] = useState(0);

  const canListAll = useCanListAllDatasources();
  // Table suggestions only — a datasource that cannot be introspected still takes free text.
  const schema = useQuery({
    queryKey: datasourceKeys.schema(datasourceId ?? ''),
    queryFn: () => getDatasourceSchema(datasourceId ?? ''),
    enabled: datasourceId != null,
    retry: false,
  });
  const tableOptions = useMemo(
    () =>
      (schema.data?.schemas ?? []).flatMap((ns) =>
        ns.tables.map((tbl) => {
          const ref = ns.name ? `${ns.name}.${tbl.name}` : tbl.name;
          return { value: ref, label: ref };
        }),
      ),
    [schema.data],
  );

  const filters: EffectiveAccessFilters | null = lookup
    ? { ...lookup, page, size: PAGE_SIZE }
    : null;
  const index = useQuery({
    queryKey: effectiveAccessKeys.list(filters ?? { datasource_id: '', table: '', capability: 'READ' }),
    queryFn: filters ? () => getEffectiveAccess(filters) : skipToken,
  });

  const columns: TableColumnsType<EffectiveAccessRow> = useMemo(
    () => [
      {
        title: t('access.effective.col_identity'),
        key: 'identity',
        render: (_: unknown, row) => (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name={row.email} size={24} />
            <div>
              <div style={{ fontSize: 13 }}>{row.display_name ?? row.email}</div>
              <div className="mono muted" style={{ fontSize: 11 }}>
                {row.email}
                {row.role_name != null && ` · ${row.role_name}`}
              </div>
            </div>
          </div>
        ),
      },
      {
        title: t('access.effective.col_granted'),
        key: 'granted',
        width: 110,
        render: (_: unknown, row) => <Flag on={row.granted} />,
      },
      {
        title: t('access.effective.col_scope'),
        key: 'scope',
        width: 150,
        render: (_: unknown, row) =>
          row.table_scope == null ? '—' : effectiveAccessTableScopeLabel(t, row.table_scope),
      },
      {
        title: t('access.effective.col_sources'),
        key: 'sources',
        render: (_: unknown, row) =>
          row.sources.length === 0 ? (
            <span className="muted">{t('decisionTrace.none')}</span>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {row.sources.map((s, i) => (
                <SourceLine key={`${s.kind}-${s.source_id ?? i}`} source={s} />
              ))}
            </div>
          ),
      },
      {
        title: t('access.effective.col_query_admin'),
        key: 'query_admin',
        width: 130,
        render: (_: unknown, row) => (
          <span data-testid={`query-admin-${row.user_id}`}>
            <Flag on={hasQueryAdminBypass(row)} />
          </span>
        ),
      },
      {
        title: t('access.effective.col_break_glass'),
        key: 'break_glass',
        width: 130,
        render: (_: unknown, row) => (
          <span data-testid={`break-glass-${row.user_id}`}>
            <Flag on={row.can_break_glass} />
          </span>
        ),
      },
      {
        title: t('access.effective.col_expires'),
        key: 'expires',
        width: 170,
        render: (_: unknown, row) =>
          row.effective_expires_at != null
            ? fmtDate(row.effective_expires_at)
            : row.granted
              ? t('decisionTrace.never_expires')
              : '—',
      },
    ],
    [t],
  );

  const onFinish = (values: LookupValues) => {
    const table = values.table?.trim();
    if (!values.datasource_id || !table) return;
    setPage(0);
    setLookup({ datasource_id: values.datasource_id, table, capability: values.capability });
  };

  const rows = index.data?.content ?? [];

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Form<LookupValues>
        form={form}
        name="effective-access"
        layout="vertical"
        initialValues={{ capability: 'WRITE' }}
        onFinish={onFinish}
      >
        <Form.Item
          name="datasource_id"
          label={t('access.simulation.datasource')}
          rules={[{ required: true, message: t('access.simulation.datasource_required') }]}
          extra={canListAll ? undefined : t('access.simulation.datasource_scoped_hint')}
          style={{ width: 280 }}
        >
          <SimulationDatasourceSelect onChange={() => form.setFieldValue('table', undefined)} />
        </Form.Item>
        <Form.Item
          name="table"
          label={t('access.effective.table')}
          rules={[{ required: true, whitespace: true, message: t('access.effective.table_required') }]}
          style={{ width: 280 }}
        >
          <AutoComplete
            options={tableOptions}
            placeholder={t('access.effective.table_placeholder')}
            showSearch={{
              filterOption: (input, option) =>
                String(option?.value ?? '').toLowerCase().includes(input.toLowerCase()),
            }}
          />
        </Form.Item>
        <Form.Item name="capability" label={t('access.effective.capability')} style={{ width: 160 }}>
          <Select options={enumOptions(STATEMENT_CAPABILITIES, statementCapabilityLabel, t)} />
        </Form.Item>
        <Form.Item label=" " colon={false}>
          <Button type="primary" htmlType="submit">
            {t('access.effective.lookup')}
          </Button>
        </Form.Item>
      </Form>

      {filters == null ? (
        <EmptyState
          size="sm"
          title={t('access.effective.prompt_title')}
          description={t('access.effective.prompt_description')}
        />
      ) : index.isLoading ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : index.isError ? (
        <EmptyState
          size="sm"
          title={t('access.effective.load_error')}
          description={apiErrorMessage(index.error, () => t('access.effective.load_error'))}
        />
      ) : rows.length === 0 ? (
        <EmptyState size="sm" title={t('access.effective.empty')} />
      ) : (
        <Table<EffectiveAccessRow>
          rowKey="user_id"
          size="middle"
          dataSource={rows}
          columns={columns}
          scroll={{ x: 'max-content' }}
          data-testid="effective-access-table"
          pagination={{
            pageSize: PAGE_SIZE,
            current: page + 1,
            total: index.data?.total_elements ?? 0,
            onChange: (p) => setPage(p - 1),
          }}
        />
      )}
    </Space>
  );
}
