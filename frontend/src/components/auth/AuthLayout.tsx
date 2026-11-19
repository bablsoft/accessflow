import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { LanguageSwitcher } from '@/components/common/LanguageSwitcher';
import { LogoMark } from '@/components/common/LogoMark';
import { APP_VERSION } from '@/config/version';
import './auth-layout.css';

interface AuthLayoutProps {
  title: ReactNode;
  subtitle?: ReactNode;
  /** The wider column, for forms with more than a couple of fields. */
  wide?: boolean;
  /** Shows the public language switcher beside the brand. */
  languageSwitcher?: boolean;
  /** Rendered under the card. */
  footer?: ReactNode;
  children: ReactNode;
}

/** The frame every signed-out page shares: brand, one centred card, optional footer. */
export function AuthLayout({
  title, subtitle, wide = false, languageSwitcher = false, footer, children,
}: AuthLayoutProps) {
  const { t } = useTranslation();
  return (
    <main className="af-auth">
      <div className={wide ? 'af-auth-column af-auth-column--wide' : 'af-auth-column'}>
        <div className="af-auth-brand">
          <span className="af-auth-logo">
            <LogoMark size={32} />
          </span>
          <div>
            <div className="af-auth-name">{t('common.app_name')}</div>
            <div
              className="af-auth-version"
              aria-label={t('nav.version', { version: APP_VERSION })}
            >
              v{APP_VERSION}
            </div>
          </div>
          {languageSwitcher && (
            <div className="af-auth-language">
              <LanguageSwitcher mode="public" />
            </div>
          )}
        </div>
        <div className="af-card">
          <div className="af-auth-heading">
            <h1 className="af-auth-title">{title}</h1>
            {subtitle && <div className="af-auth-subtitle">{subtitle}</div>}
          </div>
          {children}
        </div>
        {footer && <div className="af-auth-footer">{footer}</div>}
      </div>
    </main>
  );
}
