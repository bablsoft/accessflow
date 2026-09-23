import { useState } from 'react';
import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { withTypedId } from './typedIdOption';
import { useVisibleDatasources } from './useSimulationDatasources';

interface Props {
  id?: string;
  value?: string;
  onChange?: (value: string) => void;
}

/**
 * Datasource picker for the simulations. The list endpoint is scoped to the caller's own
 * datasources unless they hold QUERY_ADMIN or DATASOURCE_MANAGE — an auditor usually holds
 * neither — so a pasted datasource id is accepted as well.
 */
export function SimulationDatasourceSelect({ id, value, onChange }: Props) {
  const { t } = useTranslation();
  const { rows, loading } = useVisibleDatasources();
  const [search, setSearch] = useState('');
  const options = withTypedId(
    rows.map((d) => ({ value: d.id, label: d.name })),
    search,
    (typed) => t('decisionTrace.use_id', { id: typed }),
  );
  return (
    <Select
      id={id}
      value={value}
      onChange={onChange}
      showSearch={{ optionFilterProp: 'label', onSearch: setSearch }}
      loading={loading}
      placeholder={t('access.simulation.datasource_placeholder')}
      options={options}
    />
  );
}
