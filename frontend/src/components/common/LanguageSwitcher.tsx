import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Dropdown, App as AntdApp } from 'antd';
import type { MenuProps } from 'antd';
import { GlobalOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getMeLocalization,
  getPublicLocalizationConfig,
  localizationKeys,
  updateMeLocalization,
} from '@/api/localization';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { isSupportedLanguage, LANGUAGE_DISPLAY_NAMES, type Language } from '@/i18n';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';

export type LanguageSwitcherMode = 'authenticated' | 'public';

interface LanguageSwitcherProps {
  mode?: LanguageSwitcherMode;
}

export function LanguageSwitcher({ mode = 'authenticated' }: LanguageSwitcherProps = {}) {
  const { t } = useTranslation();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const language = usePreferencesStore((s) => s.language);
  const setLanguage = usePreferencesStore((s) => s.setLanguage);
  const queryClient = useQueryClient();
  const { message } = AntdApp.useApp();

  const publicMode = mode === 'public';

  const meQuery = useQuery({
    queryKey: localizationKeys.me(),
    queryFn: getMeLocalization,
    enabled: !publicMode && isAuthenticated,
  });

  const publicQuery = useQuery({
    queryKey: localizationKeys.public(),
    queryFn: getPublicLocalizationConfig,
    enabled: publicMode,
  });

  const mutation = useMutation({
    mutationFn: (code: string) => updateMeLocalization(code),
    onSuccess: (payload) => {
      setLanguage(payload.current_language);
      queryClient.setQueryData(localizationKeys.me(), payload);
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('errors.languages_save_error')));
    },
  });

  const sourceLanguages = publicMode
    ? publicQuery.data?.available_languages
    : meQuery.data?.available_languages;
  const supported = sourceLanguages?.filter(isSupportedLanguage) as Language[] | undefined;

  const items: MenuProps['items'] = (supported ?? ['en']).map((code) => ({
    key: code,
    label: LANGUAGE_DISPLAY_NAMES[code] ?? code,
    onClick: () => {
      if (code === language) return;
      setLanguage(code);
      if (!publicMode && isAuthenticated) {
        mutation.mutate(code);
      }
    },
  }));

  const current = LANGUAGE_DISPLAY_NAMES[language] ?? language;

  return (
    <Dropdown menu={{ items, selectedKeys: [language] }} trigger={['click']}>
      <button
        type="button"
        className="af-language-switcher"
        aria-label={t('common.language')}
        title={current}
      >
        <GlobalOutlined />
        <span className="af-language-switcher-code">{current}</span>
      </button>
    </Dropdown>
  );
}
