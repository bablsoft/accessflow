import { useState } from 'react';
import { Input, Select } from 'antd';
import {
  SearchOutlined,
  RightOutlined,
  DownOutlined,
  TableOutlined,
} from '@ant-design/icons';
import type { Datasource, DatasourceSchema } from '@/types/api';

interface SchemaTreeProps {
  ds: Datasource;
  schema: DatasourceSchema;
  datasources: Datasource[];
  onChangeDs: (id: string) => void;
}

export function SchemaTree({ ds, schema, datasources, onChangeDs }: SchemaTreeProps) {
  const [filter, setFilter] = useState('');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({ public: true });
  const [openTable, setOpenTable] = useState<string | null>(null);

  return (
    <div
      style={{
        borderRight: '1px solid var(--border)',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--bg-sunken)',
        overflow: 'hidden',
      }}
    >
      <div style={{ padding: 12, borderBottom: '1px solid var(--border)' }}>
        <label
          className="muted"
          style={{ display: 'block', fontSize: 11.5, fontWeight: 500, marginBottom: 5 }}
        >
          Datasource
        </label>
        <Select
          size="small"
          style={{ width: '100%' }}
          value={ds.id}
          onChange={onChangeDs}
          options={datasources.map((d) => ({ value: d.id, label: d.name }))}
        />
        <div
          className="mono muted"
          style={{
            fontSize: 10,
            marginTop: 8,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <span
            style={{
              width: 6,
              height: 6,
              borderRadius: '50%',
              background: 'var(--risk-low)',
            }}
          />
          {ds.db_type} · {ds.host}:{ds.port}
        </div>
      </div>
      <div style={{ padding: '8px 12px', borderBottom: '1px solid var(--border)' }}>
        <Input
          size="small"
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder="Filter tables…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
      </div>
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: '6px 0',
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
        }}
      >
        {schema.schemas.map((s) => {
          const open = expanded[s.name];
          return (
            <div key={s.name}>
              <button
                onClick={() => setExpanded((e) => ({ ...e, [s.name]: !e[s.name] }))}
                style={{
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
                }}
              >
                {open ? (
                  <DownOutlined style={{ fontSize: 9 }} />
                ) : (
                  <RightOutlined style={{ fontSize: 9 }} />
                )}
                {s.name}
              </button>
              {open &&
                s.tables
                  .filter((t) => !filter || t.name.toLowerCase().includes(filter.toLowerCase()))
                  .map((tab) => {
                    const isOpen = openTable === tab.name;
                    return (
                      <div key={tab.name}>
                        <div
                          onClick={() => setOpenTable(isOpen ? null : tab.name)}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 6,
                            padding: '3px 12px 3px 24px',
                            cursor: 'pointer',
                            color: 'var(--fg)',
                            background: isOpen ? 'var(--bg-hover)' : 'transparent',
                          }}
                        >
                          <TableOutlined style={{ fontSize: 11, color: 'var(--sql-table)' }} />
                          <span>{tab.name}</span>
                          <span style={{ marginLeft: 'auto', fontSize: 10, color: 'var(--fg-faint)' }}>
                            {tab.columns.length}
                          </span>
                        </div>
                        {isOpen &&
                          tab.columns.map((c) => (
                            <div
                              key={c.name}
                              style={{
                                padding: '2px 12px 2px 40px',
                                display: 'flex',
                                alignItems: 'center',
                                gap: 6,
                                fontSize: 11,
                              }}
                            >
                              {c.primary_key ? (
                                <span style={{ color: 'var(--risk-med)', fontSize: 9 }}>PK</span>
                              ) : (
                                <span style={{ width: 12 }} />
                              )}
                              <span>{c.name}</span>
                              <span
                                style={{
                                  marginLeft: 'auto',
                                  color: 'var(--fg-faint)',
                                  fontSize: 10,
                                }}
                              >
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
