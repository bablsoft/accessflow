import { Skeleton } from 'antd';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import { ErDiagram } from './ErDiagram';

interface ErDiagramTabProps {
  dsId: string;
}

export function ErDiagramTab({ dsId }: ErDiagramTabProps) {
  const { t } = useTranslation();
  const schemaQuery = useSchemaIntrospect(dsId);

  if (schemaQuery.isLoading) {
    return (
      <div className="u-p-gutter" data-testid="er-diagram-skeleton">
        <Skeleton.Node active className="u-w-full" style={{ height: 560 }} />
        <div className="muted u-mt-3 u-text-xs">
          {t('datasources.settings.er_diagram_loading')}
        </div>
      </div>
    );
  }

  if (schemaQuery.isError || !schemaQuery.data) {
    return (
      <div className="u-p-gutter">
        <EmptyState title={t('datasources.settings.er_diagram_error')} />
      </div>
    );
  }

  const hasForeignKeys = schemaQuery.data.schemas.some((s) =>
    s.tables.some((t) => t.foreign_keys.length > 0),
  );

  if (!hasForeignKeys) {
    return (
      <div className="u-p-gutter">
        <EmptyState
          title={t('datasources.settings.er_diagram_empty_title')}
          description={t('datasources.settings.er_diagram_empty_description')}
        />
      </div>
    );
  }

  return (
    <div className="u-p-gutter">
      <ErDiagram
        schemas={schemaQuery.data.schemas}
        helpText={t('datasources.settings.er_diagram_help')}
      />
    </div>
  );
}
