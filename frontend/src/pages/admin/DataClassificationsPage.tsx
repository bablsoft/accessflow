import { Skeleton, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  dataClassificationKeys,
  listOrganizationClassifications,
} from '@/api/dataClassifications';
import { dataClassificationLabel } from '@/utils/enumLabels';
import { fmtDate } from '@/utils/dateFormat';
import type { DataClassification, OrganizationDataClassification } from '@/types/api';

export function DataClassificationsPage() {
  const { t } = useTranslation();

  const query = useQuery({
    queryKey: dataClassificationKeys.orgAll,
    queryFn: () => listOrganizationClassifications(),
  });
  const rows = query.data ?? [];

  return (
    <div>
      <PageHeader
        docsAnchor="cfg-data-classifications"
        title={t('admin.data_classifications.title')}
        subtitle={t('admin.data_classifications.subtitle')}
      />
      <div style={{ padding: '0 24px 24px' }}>
        {query.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : rows.length === 0 ? (
          <EmptyState
            title={t('admin.data_classifications.empty_title')}
            description={t('admin.data_classifications.empty_description')}
          />
        ) : (
          <Table<OrganizationDataClassification>
            rowKey="id"
            size="middle"
            dataSource={rows}
            pagination={{ pageSize: 25, hideOnSinglePage: true }}
            scroll={{ x: 'max-content' }}
            columns={[
              {
                title: t('admin.data_classifications.col_datasource'),
                dataIndex: 'datasource_name',
                render: (v: string | null) =>
                  v ?? <span className="muted">—</span>,
              },
              {
                title: t('admin.data_classifications.col_object'),
                render: (_v, row) => (
                  <span className="mono" style={{ fontSize: 12 }}>
                    {row.column_name ? `${row.table_name}.${row.column_name}` : row.table_name}
                  </span>
                ),
              },
              {
                title: t('admin.data_classifications.col_classification'),
                dataIndex: 'classification',
                width: 150,
                render: (v: DataClassification) => (
                  <Tag color="volcano">{dataClassificationLabel(t, v)}</Tag>
                ),
              },
              {
                title: t('admin.data_classifications.col_note'),
                dataIndex: 'note',
                render: (v: string | null) =>
                  v ? <span style={{ fontSize: 12 }}>{v}</span> : <span className="muted">—</span>,
              },
              {
                title: t('admin.data_classifications.col_created'),
                dataIndex: 'created_at',
                width: 180,
                render: (v: string) => <span style={{ fontSize: 12 }}>{fmtDate(v)}</span>,
              },
            ]}
          />
        )}
      </div>
    </div>
  );
}

export default DataClassificationsPage;
