import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { DriverStatusBadge } from './DriverStatusBadge';
import type { DatasourceTypeOption, DbType } from '@/types/api';

interface DatasourceTypeSelectorProps {
  types: DatasourceTypeOption[];
  selectedCode: DbType | null;
  onSelect: (option: DatasourceTypeOption) => void;
}

export function DatasourceTypeSelector({
  types,
  selectedCode,
  onSelect,
}: DatasourceTypeSelectorProps) {
  const { t } = useTranslation();

  return (
    <div
      role="radiogroup"
      aria-label={t('datasources.create.select_database')}
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
        gap: 14,
      }}
    >
      {types.map((option) => {
        const disabled = option.driver_status === 'UNAVAILABLE';
        const selected = selectedCode === option.code;
        const card = (
          <button
            type="button"
            role="radio"
            aria-checked={selected}
            aria-disabled={disabled}
            disabled={disabled}
            onClick={() => !disabled && onSelect(option)}
            key={option.code}
            style={{
              all: 'unset',
              display: 'flex',
              flexDirection: 'column',
              gap: 12,
              padding: 16,
              borderRadius: 'var(--radius-md)',
              border: `1px solid ${selected ? 'var(--accent)' : 'var(--border)'}`,
              background: selected ? 'var(--accent-bg)' : 'var(--bg-elev)',
              cursor: disabled ? 'not-allowed' : 'pointer',
              opacity: disabled ? 0.55 : 1,
              transition: 'border-color 0.15s, background 0.15s',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <img
                src={option.icon_url}
                alt=""
                width={36}
                height={36}
                style={{
                  borderRadius: 8,
                  background: 'var(--bg-sunken)',
                  padding: 4,
                  objectFit: 'contain',
                }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 14 }}>{option.display_name}</div>
                <div className="muted mono" style={{ fontSize: 11 }}>
                  {t('datasources.create.default_port_label', { port: option.default_port })}
                </div>
              </div>
            </div>
            <div className="muted" style={{ fontSize: 12, lineHeight: 1.4 }}>
              {t(`datasources.create.type_description.${option.code.toLowerCase()}`)}
            </div>
            <div>
              <DriverStatusBadge status={option.driver_status} size="sm" />
            </div>
          </button>
        );

        return disabled ? (
          <Tooltip
            key={option.code}
            title={t('datasources.create.driver_status_unavailable_tooltip')}
          >
            <div>{card}</div>
          </Tooltip>
        ) : (
          card
        );
      })}
    </div>
  );
}
