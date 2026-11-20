import { useMemo, useState } from 'react';
import { Input, Tooltip } from 'antd';
import {
  SearchOutlined,
  RightOutlined,
  DownOutlined,
  TableOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { StatusTag } from '@/components/layout';
import { filterSchema } from '@/utils/schemaFilter';
import type { DataClassification, SchemaNamespace } from '@/types/api';

interface SchemaObjectTreeProps {
  schemas: SchemaNamespace[];
  /** When provided, each table renders a "preview data" action invoking this callback. */
  onPreview?: (schema: string, table: string) => void;
  /** The currently previewed table (schema + table), highlighted in the tree. */
  selected?: { schema: string; table: string } | null;
  /**
   * Data-classification tags keyed by lowercase `table` (table-level) and `table.column`
   * (column-level); when present, classified objects render their classifications as badges.
   */
  tagsByObject?: Map<string, DataClassification[]>;
}

function classificationsFor(
  tagsByObject: Map<string, DataClassification[]> | undefined,
  key: string,
): DataClassification[] {
  return tagsByObject?.get(key.toLowerCase()) ?? [];
}

function ClassificationBadges({ classifications }: { classifications: DataClassification[] }) {
  if (classifications.length === 0) {
    return null;
  }
  return (
    <>
      {classifications.map((c) => (
        <StatusTag tone="danger" key={c} className="u-text-2xs" style={{ marginInlineEnd: 0, lineHeight: '14px', padding: '0 4px' }}>
          {c}
        </StatusTag>
      ))}
    </>
  );
}

/**
 * Searchable, hierarchical schema browser (schemas → tables → columns). The filter matches across
 * all three levels (AF-443). Used by both the datasource settings Schema tab and the editor sidebar.
 */
export function SchemaObjectTree({ schemas, onPreview, selected, tagsByObject }: SchemaObjectTreeProps) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState('');
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [openTable, setOpenTable] = useState<string | null>(null);

  const filtered = useMemo(() => filterSchema({ schemas }, filter).schemas, [schemas, filter]);
  const filtering = filter.trim().length > 0;

  return (
    <div className="u-flex u-col" style={{ minHeight: 0, height: '100%' }}>
      <div style={{ padding: '8px 12px', borderBottom: '1px solid var(--border)' }}>
        <Input
          size="small"
          allowClear
          prefix={<SearchOutlined className="u-fg-faint" />}
          placeholder={t('datasources.settings.object_tree_search_placeholder')}
          aria-label={t('datasources.settings.object_tree_search_placeholder')}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
      </div>
      <div
        className="u-flex-1 u-mono u-text-xs" style={{ overflow: 'auto', padding: '6px 0' }}
      >
        {filtered.length === 0 && (
          <div className="muted u-text-2xs" style={{ padding: '8px 12px' }}>
            {t('datasources.settings.object_tree_no_match', { query: filter })}
          </div>
        )}
        {filtered.map((s) => {
          // When filtering, force-expand so matches are visible; otherwise honour the toggle.
          const open = filtering || !collapsed[s.name];
          return (
            <div key={s.name}>
              <button
                type="button"
                onClick={() => setCollapsed((c) => ({ ...c, [s.name]: !c[s.name] }))}
                style={schemaHeaderStyle}
              >
                {open ? (
                  <DownOutlined style={{ fontSize: 9 }} />
                ) : (
                  <RightOutlined style={{ fontSize: 9 }} />
                )}
                {s.name}
              </button>
              {open &&
                s.tables.map((tab) => {
                  const isOpen = openTable === `${s.name}.${tab.name}`;
                  const isSelected =
                    selected?.schema === s.name && selected?.table === tab.name;
                  return (
                    <div key={tab.name}>
                      <div
                        className="u-flex u-items-center u-fg" style={{ gap: 6, padding: '3px 12px 3px 24px', background: isSelected ? 'var(--bg-hover)' : 'transparent' }}
                      >
                        <button
                          type="button"
                          onClick={() => setOpenTable(isOpen ? null : `${s.name}.${tab.name}`)}
                          aria-expanded={isOpen}
                          // Explicit label so the accessible name is exactly the table name —
                          // the visible column-count badge below would otherwise leak into it.
                          aria-label={tab.name}
                          style={tableButtonStyle}
                        >
                          <TableOutlined className="u-text-2xs" style={{ color: 'var(--sql-table)' }} />
                          <span>{tab.name}</span>
                          <span className="u-text-2xs u-fg-faint">
                            {tab.columns.length}
                          </span>
                        </button>
                        <ClassificationBadges
                          classifications={classificationsFor(tagsByObject, tab.name)}
                        />
                        {onPreview && (
                          <Tooltip title={t('datasources.settings.sample_preview_button')}>
                            <button
                              type="button"
                              onClick={() => onPreview(s.name, tab.name)}
                              aria-label={t('datasources.settings.sample_preview_aria', {
                                table: tab.name,
                              })}
                              style={previewButtonStyle}
                            >
                              <EyeOutlined className="u-text-xs" />
                            </button>
                          </Tooltip>
                        )}
                      </div>
                      {isOpen &&
                        tab.columns.map((c) => (
                          <div key={c.name} style={columnRowStyle}>
                            {c.primary_key ? (
                              <span className="u-text-2xs" style={{ color: 'var(--risk-med)' }}>PK</span>
                            ) : (
                              <span style={{ width: 12 }} />
                            )}
                            <span>{c.name}</span>
                            <ClassificationBadges
                              classifications={classificationsFor(
                                tagsByObject,
                                `${tab.name}.${c.name}`,
                              )}
                            />
                            <span className="u-ml-auto u-fg-faint u-text-2xs">
                              {c.type}
                            </span>
                          </div>
                        ))}
                    </div>
                  );
                })}
            </div>
          );
        })}
      </div>
    </div>
  );
}

const schemaHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  width: '100%',
  padding: '4px 12px',
  background: 'transparent',
  border: 'none',
  color: 'var(--fg-muted)',
  fontFamily: 'inherit',
  fontSize: 11,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  cursor: 'pointer',
};

const tableButtonStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flex: 1,
  minWidth: 0,
  background: 'transparent',
  border: 'none',
  color: 'inherit',
  fontFamily: 'inherit',
  fontSize: 'inherit',
  padding: 0,
  cursor: 'pointer',
  textAlign: 'left',
};

const previewButtonStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: 'var(--fg-faint)',
  cursor: 'pointer',
  padding: '0 2px',
  display: 'flex',
  alignItems: 'center',
};

const columnRowStyle: React.CSSProperties = {
  padding: '2px 12px 2px 40px',
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 11,
};
