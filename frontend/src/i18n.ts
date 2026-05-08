import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import es from './locales/es.json';
import de from './locales/de.json';
import fr from './locales/fr.json';
import zhCN from './locales/zh-CN.json';
import ru from './locales/ru.json';
import hy from './locales/hy.json';

export const SUPPORTED_LANGUAGES = ['en', 'es', 'de', 'fr', 'zh-CN', 'ru', 'hy'] as const;
export type Language = (typeof SUPPORTED_LANGUAGES)[number];

export const LANGUAGE_DISPLAY_NAMES: Record<Language, string> = {
  en: 'English',
  es: 'Español',
  de: 'Deutsch',
  fr: 'Français',
  'zh-CN': '简体中文',
  ru: 'Русский',
  hy: 'Հայերեն',
};

export function isSupportedLanguage(code: string | null | undefined): code is Language {
  return !!code && (SUPPORTED_LANGUAGES as readonly string[]).includes(code);
}

const persistedLanguage = (() => {
  if (typeof window === 'undefined') return 'en';
  try {
    const raw = window.localStorage.getItem('af-preferences');
    if (!raw) return 'en';
    const parsed = JSON.parse(raw);
    const code = parsed?.state?.language;
    return isSupportedLanguage(code) ? code : 'en';
  } catch {
    return 'en';
  }
})();

i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    es: { translation: es },
    de: { translation: de },
    fr: { translation: fr },
    'zh-CN': { translation: zhCN },
    ru: { translation: ru },
    hy: { translation: hy },
  },
  lng: persistedLanguage,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export default i18n;
