import { Drawer } from 'antd';
import { useTranslation } from 'react-i18next';
import { SampleDataPreview } from './SampleDataPreview';

interface SampleDataDrawerProps {
  datasourceId: string;
  target: { schema: string; table: string } | null;
  onClose: () => void;
}

/** Right-side drawer hosting a {@link SampleDataPreview} for the selected table (AF-443). */
export function SampleDataDrawer({ datasourceId, target, onClose }: SampleDataDrawerProps) {
  const { t } = useTranslation();
  return (
    <Drawer
      width={720}
      open={target !== null}
      onClose={onClose}
      title={
        target
          ? t('datasources.settings.sample_title', { table: target.table })
          : t('datasources.settings.tab_schema')
      }
      destroyOnHidden
    >
      {target && (
        <SampleDataPreview datasourceId={datasourceId} schema={target.schema} table={target.table} />
      )}
    </Drawer>
  );
}
