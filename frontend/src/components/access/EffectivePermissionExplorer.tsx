import { useState } from 'react';
import { Alert, Card, Descriptions, Form, Skeleton, Space, Table, Tag } from 'antd';
import { skipToken, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { effectiveAccessKeys, getEffectivePermission } from '@/api/accessSimulations';
import { EmptyState } from '@/components/common/EmptyState';
import { SimulationDatasourceSelect } from '@/components/policies/SimulationDatasourceSelect';
import { SimulationUserSelect } from '@/components/policies/SimulationUserSelect';
import { apiErrorMessage } from '@/utils/apiErrors';
import { fmtDate, fmtNum } from '@/utils/dateFormat';
import { formatBytes } from '@/utils/queryPlan';
import {
  QUERY_SHAPES,
  effectiveCapabilityLabel,
  maskingStrategyLabel,
  queryShapeLabel,
  rowCapSourceLabel,
} from '@/utils/enumLabels';
import type {
  AccessTargetMatch,
  EffectiveAttributedValue,
  EffectivePermission,
  EffectivePermissionGrant,
  EffectivePermissionScope,
  QueryShape,
} from '@/types/api';
import { formatRowSecurityPredicate } from './effectivePermission';

interface ExplorerProps {
  /** Fixes the user side (the Users page drawer); the datasource is picked. */
  userId?: string;
  /** Fixes the datasource side (the datasource settings tab); the user is picked. */
  datasourceId?: string;
}

/**
 * The effective-permission explorer (#946): what one user can do on one datasource right now, and
 * which grant, group or policy each element comes from. Read-only — every value is resolved by the
 * backend's enforcement services; nothing here merges or clamps.
 */
export function EffectivePermissionExplorer({ userId, datasourceId }: ExplorerProps) {
  const { t } = useTranslation();
  const [pickedUser, setPickedUser] = useState<string | undefined>();
  const [pickedDatasource, setPickedDatasource] = useState<string | undefined>();
  const effectiveUser = userId ?? pickedUser;
  const effectiveDatasource = datasourceId ?? pickedDatasource;

  const query = useQuery({
    queryKey:
      effectiveUser && effectiveDatasource
        ? effectiveAccessKeys.explanation(effectiveUser, effectiveDatasource)
        : ['effective-access', 'explanation', 'idle'],
    queryFn:
      effectiveUser && effectiveDatasource
        ? () => getEffectivePermission(effectiveUser, effectiveDatasource)
        : skipToken,
    retry: false,
  });

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Alert type="info" showIcon title={t('access.explorer.intro')} />
      <Form layout="vertical" name="effective-permission-explorer">
        <Space wrap size="middle" align="start">
          {userId === undefined && (
            <Form.Item
              label={t('access.explorer.pick_user')}
              htmlFor="effective-access-user"
              style={{ width: 320 }}
            >
              <SimulationUserSelect
                id="effective-access-user"
                value={pickedUser}
                onChange={setPickedUser}
              />
            </Form.Item>
          )}
          {datasourceId === undefined && (
            <Form.Item
              label={t('access.explorer.pick_datasource')}
              htmlFor="effective-access-datasource"
              style={{ width: 320 }}
            >
              <SimulationDatasourceSelect
                id="effective-access-datasource"
                value={pickedDatasource}
                onChange={setPickedDatasource}
              />
            </Form.Item>
          )}
        </Space>
      </Form>

      {!(effectiveUser && effectiveDatasource) && (
        <EmptyState size="sm" title={t('access.explorer.pick_prompt')} />
      )}
      {query.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}
      {query.isError && (
        <EmptyState
          size="sm"
          title={t('access.explorer.error')}
          description={apiErrorMessage(query.error, () => t('access.explorer.error'))}
        />
      )}
      {query.data && <EffectivePermissionView data={query.data} />}
    </Space>
  );
}

function grantLabel(grant: EffectivePermissionGrant | undefined, t: TFunction): string {
  if (!grant) return t('access.explorer.source_unknown');
  if (grant.source_kind === 'DIRECT') return t('access.explorer.source_direct');
  return grant.group_name
    ? t('access.explorer.source_group', { group: grant.group_name })
    : t('access.explorer.source_group_unnamed');
}

function SourceTags({ ids, grants }: { ids: string[]; grants: Map<string, EffectivePermissionGrant> }) {
  const { t } = useTranslation();
  if (ids.length === 0) return <span className="muted">—</span>;
  return (
    <Space size={4} wrap>
      {ids.map((id) => {
        const grant = grants.get(id);
        return (
          <Tag key={id} color={grant?.source_kind === 'GROUP' ? 'blue' : 'default'}>
            {grantLabel(grant, t)}
            {grant?.expires_at ? ` · ${t('access.explorer.expires_on', { date: fmtDate(grant.expires_at) })}` : ''}
          </Tag>
        );
      })}
    </Space>
  );
}

function matchText(match: AccessTargetMatch, t: TFunction): string {
  switch (match.kind) {
    case 'EVERYONE':
      return t('access.explorer.match_everyone');
    case 'ROLE':
      return t('access.explorer.match_role', { name: match.ref ?? '' });
    case 'GROUP':
      return t('access.explorer.match_group', { name: match.name ?? match.ref ?? '' });
    case 'USER':
      return t('access.explorer.match_user');
  }
}

function matchesText(matches: AccessTargetMatch[], t: TFunction): string {
  return matches.map((m) => matchText(m, t)).join(', ');
}

function AttributedValues({
  values,
  grants,
  render = (v) => v,
}: {
  values: EffectiveAttributedValue[];
  grants: Map<string, EffectivePermissionGrant>;
  render?: (value: string) => string;
}) {
  const { t } = useTranslation();
  if (values.length === 0) return <span className="muted">{t('access.explorer.none')}</span>;
  return (
    <Space orientation="vertical" size={4}>
      {values.map((v) => (
        <Space key={v.value} size={8} wrap>
          <span className="mono">{render(v.value)}</span>
          <SourceTags ids={v.grant_ids} grants={grants} />
        </Space>
      ))}
    </Space>
  );
}

function scopeValues(scope: EffectivePermissionScope): EffectiveAttributedValue[] {
  return scope.unrestricted ? [] : scope.entries;
}

function RowCapCard({
  data,
  grants,
}: {
  data: EffectivePermission;
  grants: Map<string, EffectivePermissionGrant>;
}) {
  const { t } = useTranslation();
  const cap = data.row_cap;
  const overrides = data.grants.filter((g) => g.row_limit_override != null);
  const clamped = cap.override != null && cap.override > cap.value;
  return (
    <Card size="small" title={t('access.explorer.section_row_cap')} data-testid="explorer-row-cap">
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, flexWrap: 'wrap' }}>
        <span
          className="mono"
          style={{ fontSize: 28, fontWeight: 600 }}
          data-testid="explorer-row-cap-value"
        >
          {fmtNum(cap.value)}
        </span>
        <Tag data-testid="explorer-row-cap-source" data-source={cap.source}>
          {rowCapSourceLabel(t, cap.source)}
        </Tag>
      </div>
      <div style={{ marginTop: 8 }} data-testid="explorer-row-cap-explanation">
        {cap.source === 'OVERRIDE' && (
          <Space size={6} wrap>
            <span>{t('access.explorer.row_cap_from_override')}</span>
            <SourceTags ids={cap.grant_ids} grants={grants} />
          </Space>
        )}
        {cap.source === 'DATASOURCE_CAP' && t('access.explorer.row_cap_from_datasource')}
        {cap.source === 'GLOBAL_CEILING' && t('access.explorer.row_cap_from_global')}
      </div>
      {clamped && (
        <div className="muted" style={{ marginTop: 6 }} data-testid="explorer-row-cap-clamped">
          {t('access.explorer.row_cap_clamped', {
            override: fmtNum(cap.override),
            cap: fmtNum(cap.value),
          })}
        </div>
      )}
      <div className="muted" style={{ marginTop: 6, fontSize: 12 }}>
        {t('access.explorer.row_cap_bounds', {
          datasource: fmtNum(cap.datasource_cap),
          global: fmtNum(cap.global_ceiling),
        })}
      </div>
      {overrides.length > 1 || clamped ? (
        <div style={{ marginTop: 10 }} data-testid="explorer-row-cap-contributions">
          <div style={{ fontWeight: 600, fontSize: 12, marginBottom: 4 }}>
            {t('access.explorer.row_cap_contributions')}
          </div>
          {overrides.map((g) => (
            <div key={g.grant_id} style={{ fontSize: 12 }}>
              {grantLabel(g, t)}: <span className="mono">{fmtNum(g.row_limit_override)}</span>
            </div>
          ))}
        </div>
      ) : null}
      {data.table_row_limits.length > 0 && (
        <div className="muted" style={{ marginTop: 10, fontSize: 12 }}>
          {t('access.explorer.row_cap_table_note')}
        </div>
      )}
      <div className="muted" style={{ marginTop: 6, fontSize: 12 }}>
        {t('access.explorer.row_cap_budget_note')}
      </div>
    </Card>
  );
}

export function EffectivePermissionView({ data }: { data: EffectivePermission }) {
  const { t } = useTranslation();
  const grants = new Map(data.grants.map((g) => [g.grant_id, g]));

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }} data-testid="effective-permission">
      {data.query_admin && (
        <Alert type="warning" showIcon title={t('access.explorer.query_admin')} />
      )}
      {!data.has_grant && (
        <Alert type="info" showIcon title={t('access.explorer.no_grant')} data-testid="explorer-no-grant" />
      )}
      {data.has_grant && (
        <div className="muted" data-testid="explorer-expiry">
          {data.expires_at
            ? t('access.explorer.expires', { date: fmtDate(data.expires_at) })
            : t('access.explorer.never_expires')}
        </div>
      )}

      <RowCapCard data={data} grants={grants} />

      <Card size="small" title={t('access.explorer.section_capabilities')}>
        <Table
          rowKey="capability"
          size="small"
          pagination={false}
          dataSource={data.capabilities}
          columns={[
            {
              title: t('access.explorer.col_capability'),
              render: (_v, c) => effectiveCapabilityLabel(t, c.capability),
            },
            {
              title: t('access.explorer.col_granted'),
              render: (_v, c) => (c.granted ? t('common.yes') : t('common.no')),
            },
            {
              title: t('access.explorer.col_source'),
              render: (_v, c) => <SourceTags ids={c.grant_ids} grants={grants} />,
            },
          ]}
        />
      </Card>

      <Card size="small" title={t('access.explorer.section_scope')}>
        <Descriptions
          column={1}
          size="small"
          items={[
            {
              key: 'allowed_schemas',
              label: t('access.explorer.allowed_schemas'),
              children: data.allowed_schemas.unrestricted ? (
                t('access.explorer.all_schemas')
              ) : (
                <AttributedValues values={scopeValues(data.allowed_schemas)} grants={grants} />
              ),
            },
            {
              key: 'allowed_tables',
              label: t('access.explorer.allowed_tables'),
              children: data.allowed_tables.unrestricted ? (
                t('access.explorer.all_tables')
              ) : (
                <AttributedValues values={scopeValues(data.allowed_tables)} grants={grants} />
              ),
            },
            {
              key: 'denied_schemas',
              label: t('access.explorer.denied_schemas'),
              children: <AttributedValues values={data.denied_schemas} grants={grants} />,
            },
            {
              key: 'denied_tables',
              label: t('access.explorer.denied_tables'),
              children: <AttributedValues values={data.denied_tables} grants={grants} />,
            },
            {
              key: 'denied_columns',
              label: t('access.explorer.denied_columns'),
              children: <AttributedValues values={data.denied_columns} grants={grants} />,
            },
            {
              key: 'denied_shapes',
              label: t('access.explorer.denied_shapes'),
              children: (
                <AttributedValues
                  values={data.denied_shapes}
                  grants={grants}
                    render={(v) =>
                    (QUERY_SHAPES as readonly string[]).includes(v)
                      ? queryShapeLabel(t, v as QueryShape)
                      : v
                  }
                />
              ),
            },
            {
              key: 'restricted_columns',
              label: t('access.explorer.restricted_columns'),
              children: <AttributedValues values={data.restricted_columns} grants={grants} />,
            },
            ...(data.bytes_scanned_limit != null
              ? [
                  {
                    key: 'bytes_limit',
                    label: t('access.explorer.bytes_limit'),
                    children: (
                      <Space size={8} wrap>
                        <span className="mono">{formatBytes(data.bytes_scanned_limit.value)}</span>
                        <SourceTags ids={data.bytes_scanned_limit.grant_ids} grants={grants} />
                      </Space>
                    ),
                  },
                ]
              : []),
          ]}
        />
      </Card>

      <Card size="small" title={t('access.explorer.section_masking')}>
        {data.masked_columns.length === 0 ? (
          <span className="muted">{t('access.explorer.none_masked')}</span>
        ) : (
          <Space orientation="vertical" size={4} data-testid="explorer-masked">
            {data.masked_columns.map((m) => (
              <div key={m.policy_id}>
                <span className="mono">{m.column_ref}</span> ·{' '}
                {maskingStrategyLabel(t, m.strategy)}
              </div>
            ))}
          </Space>
        )}
        {(data.retention_masks ?? []).length > 0 && (
          <div style={{ marginTop: 10 }} data-testid="explorer-retention-masks">
            <div style={{ fontWeight: 600, fontSize: 12, marginBottom: 4 }}>
              {t('access.explorer.retention_masks')}
            </div>
            {(data.retention_masks ?? []).map((m) => (
              <div key={m.policy_id} style={{ fontSize: 12 }}>
                <span className="mono">{m.column_ref}</span> ·{' '}
                {maskingStrategyLabel(t, m.strategy)}
              </div>
            ))}
          </div>
        )}
        {data.revealed_masks.length > 0 && (
          <div style={{ marginTop: 10 }}>
            <div style={{ fontWeight: 600, fontSize: 12, marginBottom: 4 }}>
              {t('access.explorer.revealed')}
            </div>
            {data.revealed_masks.map((m) => (
              <div key={m.policy_id} style={{ fontSize: 12 }}>
                <span className="mono">{m.column_ref}</span> ·{' '}
                {t('access.explorer.revealed_by', { reasons: matchesText(m.revealed_by, t) })}
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card size="small" title={t('access.explorer.section_row_security')}>
        {data.row_security.length === 0 && (data.soft_delete_filters ?? []).length === 0 ? (
          <span className="muted">{t('access.explorer.none_row_security')}</span>
        ) : (
          <Space orientation="vertical" size={8} data-testid="explorer-row-security">
            {data.row_security.map((r) => (
              <div key={r.policy_id}>
                <div className="mono">
                  <span className="muted">{r.table_ref}: </span>
                  {r.values.length === 0 && r.value_type === 'VARIABLE'
                    ? `${r.column_name} …`
                    : formatRowSecurityPredicate(
                        r.column_name,
                        r.operator,
                        r.values,
                      )}
                </div>
                <div className="muted" style={{ fontSize: 12 }}>
                  {r.value_type === 'VARIABLE' &&
                    (r.values.length === 0
                      ? t('access.explorer.predicate_fail_closed', { expression: r.value_expression })
                      : t('access.explorer.value_from', { expression: r.value_expression }))}
                  {r.value_type === 'VARIABLE' ? ' · ' : ''}
                  {t('access.explorer.applies_via', { reasons: matchesText(r.matched_by, t) })}
                </div>
              </div>
            ))}
            {(data.soft_delete_filters ?? []).map((f) => (
              <div key={f.policy_id} data-testid="explorer-soft-delete">
                <div className="mono">
                  <span className="muted">{f.table_ref}: </span>
                  {`${f.column_name} IS NULL`}
                </div>
                <div className="muted" style={{ fontSize: 12 }}>
                  {t('access.explorer.soft_delete_filter')}
                </div>
              </div>
            ))}
          </Space>
        )}
      </Card>

      <Card size="small" title={t('access.explorer.section_table_limits')}>
        {data.table_row_limits.length === 0 ? (
          <span className="muted">{t('access.explorer.none_table_limits')}</span>
        ) : (
          <Space orientation="vertical" size={4}>
            {data.table_row_limits.map((p) => (
              <div key={p.policy_id}>
                <span className="mono">
                  {p.schema_name ? `${p.schema_name}.${p.table_name}` : p.table_name}
                </span>{' '}
                · {t('access.explorer.table_limit_rows', { count: p.max_rows })} ·{' '}
                <span className="muted">
                  {t('access.explorer.applies_via', { reasons: matchesText(p.matched_by, t) })}
                </span>
              </div>
            ))}
          </Space>
        )}
      </Card>

      <Card size="small" title={t('access.explorer.section_grants')}>
        {data.grants.length === 0 ? (
          <span className="muted">{t('access.explorer.none')}</span>
        ) : (
          <Table<EffectivePermissionGrant>
            rowKey="grant_id"
            size="small"
            pagination={false}
            dataSource={data.grants}
            columns={[
              { title: t('access.explorer.col_source'), render: (_v, g) => grantLabel(g, t) },
              {
                title: t('access.explorer.col_row_limit'),
                render: (_v, g) =>
                  g.row_limit_override != null ? (
                    <span className="mono">{fmtNum(g.row_limit_override)}</span>
                  ) : (
                    <span className="muted">{t('access.explorer.no_override')}</span>
                  ),
              },
              {
                title: t('access.explorer.col_expires'),
                render: (_v, g) =>
                  g.expires_at ? fmtDate(g.expires_at) : t('access.explorer.never'),
              },
            ]}
          />
        )}
      </Card>
    </Space>
  );
}
