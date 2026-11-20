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
      className="u-flex u-col u-bg-sunken u-overflow-hidden" style={{ borderRight: '1px solid var(--border)' }}
    >
      <div className="u-p-3 u-border-b">
        <label
          className="muted u-block u-text-xs u-fw-500"
          style={{ marginBottom: 5 }}
        >
          {t('editor.datasource_label')}
        </label>
        <Select
          size="small"
          className="u-w-full"
          showSearch={{ optionFilterProp: 'label' }}
          value={ds.id}
          onChange={onChangeDs}
          options={datasources.map((d) => ({ value: d.id, label: d.name }))}
        />
        <div
          className="mono muted u-text-2xs u-mt-2 u-flex u-items-center"
          style={{ gap: 6 }}
        >
          <span
            className="u-rounded-full" style={{ width: 6, height: 6, background: 'var(--risk-low)' }}
          />
          {dbTypeLabel(t, ds.db_type)} · {ds.host}:{ds.port}
        </div>
      </div>
      {schemaQuery.isLoading && (
        <div className="muted u-text-2xs u-py-2 u-px-3">
          {t('datasources.settings.schema_loading')}
        </div>
      )}
      {schemaQuery.isError && (
        <div
          className="muted u-text-2xs u-py-2 u-px-3 u-fg-risk-high"
        >
          {t('datasources.settings.schema_error')}
        </div>
      )}
      {schemaQuery.data && (
        <div className="u-flex-1 u-min-h-0">
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
