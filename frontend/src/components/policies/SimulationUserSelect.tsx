import { useState } from 'react';
import { Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { listUsers, userKeys } from '@/api/admin';
import { renderUserOption } from '@/components/common/renderUserOption';
import { useAuthStore } from '@/store/authStore';
import { hasPermission } from '@/utils/permissions';
import { userSelectOptions } from '@/utils/userOptions';
import { withTypedId } from './typedIdOption';

// Newest first (the endpoint's default sort), so a just-created identity is always listed.
const PICKER_FILTERS = { size: 100 };

interface Props {
  id?: string;
  value?: string;
  onChange?: (value: string) => void;
}

/**
 * The simulated-user picker shared by the three decision-trace forms. Service accounts are
 * offered too: an agent's request is traced exactly like a person's. The user list needs
 * USER_MANAGE; without it — or for an identity outside the 100 newest — a pasted user id works.
 */
export function SimulationUserSelect({ id, value, onChange }: Props) {
  const { t } = useTranslation();
  const canList = hasPermission(
    useAuthStore((s) => s.user),
    'USER_MANAGE',
  );
  const [search, setSearch] = useState('');
  const users = useQuery({
    queryKey: userKeys.list(PICKER_FILTERS),
    queryFn: () => listUsers(PICKER_FILTERS),
    enabled: canList,
  });
  const listed = userSelectOptions((users.data?.content ?? []).filter((u) => u.active));
  const options = withTypedId(listed, search, (typed) => t('decisionTrace.use_id', { id: typed }));
  return (
    <Select
      id={id}
      value={value}
      onChange={onChange}
      showSearch={{ optionFilterProp: 'label', onSearch: setSearch }}
      loading={users.isLoading && canList}
      options={options}
      optionRender={renderUserOption}
      placeholder={
        canList ? t('decisionTrace.user_placeholder') : t('decisionTrace.user_id_placeholder')
      }
    />
  );
}
