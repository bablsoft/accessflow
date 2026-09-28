import { Drawer } from 'antd';
import { useTranslation } from 'react-i18next';
import { EffectivePermissionExplorer } from '@/components/access/EffectivePermissionExplorer';
import type { User } from '@/types/api';
import { userDisplay } from '@/utils/userDisplay';

interface Props {
  user: User | null;
  onClose: () => void;
}

/**
 * One user's effective access on a datasource they pick (#946). There is no user detail page, so —
 * like the data-usage drawer — it opens from the users list.
 */
export function UserEffectiveAccessDrawer({ user, onClose }: Props) {
  const { t } = useTranslation();
  return (
    <Drawer
      open={!!user}
      onClose={onClose}
      size={760}
      title={
        user
          ? t('access.explorer.drawer_title', { name: userDisplay(user.display_name, user.email) })
          : ''
      }
      destroyOnHidden
    >
      {user && <EffectivePermissionExplorer userId={user.id} />}
    </Drawer>
  );
}
