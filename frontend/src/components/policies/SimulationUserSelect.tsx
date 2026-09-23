import { Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { listUsers, userKeys } from '@/api/admin';
import { renderUserOption } from '@/components/common/renderUserOption';
import { userSelectOptions } from '@/utils/userOptions';

const PICKER_FILTERS = { size: 100 };

interface Props {
  id?: string;
  value?: string;
  onChange?: (value: string) => void;
}

/**
 * The simulated-user picker shared by the three decision-trace forms. Service accounts are
 * offered too: an agent's request is traced exactly like a person's.
 */
export function SimulationUserSelect({ id, value, onChange }: Props) {
  const { t } = useTranslation();
  const users = useQuery({
    queryKey: userKeys.list(PICKER_FILTERS),
    queryFn: () => listUsers(PICKER_FILTERS),
  });
  const options = userSelectOptions((users.data?.content ?? []).filter((u) => u.active));
  return (
    <Select
      id={id}
      value={value}
      onChange={onChange}
      showSearch={{ optionFilterProp: 'label' }}
      loading={users.isLoading}
      options={options}
      optionRender={renderUserOption}
      placeholder={t('decisionTrace.user_placeholder')}
      aria-label={t('decisionTrace.user')}
    />
  );
}
