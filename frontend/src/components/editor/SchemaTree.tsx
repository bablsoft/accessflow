import { useState } from 'react';
import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import { dbTypeLabel } from '@/utils/enumLabels';
import { SchemaObjectTree } from '@/components/datasources/SchemaObjectTree';
import { SampleDataDrawer } from '@/components/datasources/SampleDataDrawer';
import type { Datasource } from '@/types/api';

interface SchemaTreeProps {
  ds: Datasource;
  datasources: Datasource[];
  onChangeDs: (id: string) => void;
}

export function SchemaTree({ ds, datasources, onChangeDs }: SchemaTreeProps) {
  const { t } = useTranslation();
  const [preview, setPreview] = useState<{ schema: string; table: string } | null>(null);
  const schemaQuery = useSchemaIntrospect(ds.id);

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
          {t('editor.datasource_label')}
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
          style={{ fontSize: 10, marginTop: 8, display: 'flex', alignItems: 'center', gap: 6 }}
        >
          <span
            style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--risk-low)' }}
          />
          {dbTypeLabel(t, ds.db_type)} · {ds.host}:{ds.port}
        </div>
      </div>
      {schemaQuery.isLoading && (
        <div className="muted" style={{ padding: '8px 12px', fontSize: 11 }}>
          {t('datasources.settings.schema_loading')}
        </div>
      )}
      {schemaQuery.isError && (
        <div
          className="muted"
          style={{ padding: '8px 12px', fontSize: 11, color: 'var(--risk-high)' }}
        >
          {t('datasources.settings.schema_error')}
        </div>
      )}
      {schemaQuery.data && (
        <div style={{ flex: 1, minHeight: 0 }}>
          <SchemaObjectTree
            schemas={schemaQuery.data.schemas}
            selected={preview}
            onPreview={(schema, table) => setPreview({ schema, table })}
          />
        </div>
      )}
      <SampleDataDrawer datasourceId={ds.id} target={preview} onClose={() => setPreview(null)} />
    </div>
  );
}
