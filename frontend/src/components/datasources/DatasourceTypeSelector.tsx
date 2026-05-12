import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { DriverStatusBadge } from './DriverStatusBadge';
import type { DatasourceTypeOption } from '@/types/api';

interface DatasourceTypeSelectorProps {
  types: DatasourceTypeOption[];
  /**
   * Currently selected option. We compare on the full row (code + custom_driver_id) so two
   * uploaded drivers that share a target db_type stay distinguishable in the UI.
   */
  selectedKey: string | null;
  onSelect: (option: DatasourceTypeOption) => void;
}

export function optionKey(option: DatasourceTypeOption): string {
  return option.source === 'uploaded' && option.custom_driver_id
    ? `uploaded:${option.custom_driver_id}`
    : `bundled:${option.code}`;
}

export function DatasourceTypeSelector({
  types,
  selectedKey,
  onSelect,
}: DatasourceTypeSelectorProps) {
  const { t } = useTranslation();

  const grouped = useMemo(() => {
    const bundled: DatasourceTypeOption[] = [];
    const uploaded: DatasourceTypeOption[] = [];
    for (const option of types) {
      if (option.source === 'uploaded') uploaded.push(option);
      else bundled.push(option);
    }
    return { bundled, uploaded };
  }, [types]);

  return (
    <div role="radiogroup" aria-label={t('datasources.create.select_database')}
      style={{ display: 'flex', flexDirection: 'column', gap: 20 }}
    >
      <TypeGroup
        title={t('datasources.create.source_bundled')}
        options={grouped.bundled}
        selectedKey={selectedKey}
        onSelect={onSelect}
      />
      <TypeGroup
        title={t('datasources.create.source_uploaded')}
        options={grouped.uploaded}
        selectedKey={selectedKey}
        onSelect={onSelect}
        emptyState={
          <div
            className="muted"
            style={{
              fontSize: 12,
              padding: 12,
              borderRadius: 'var(--radius-md)',
              border: '1px dashed var(--border)',
              background: 'var(--bg-sunken)',
            }}
          >
            <div style={{ marginBottom: 6 }}>
              {t('datasources.create.no_custom_drivers_hint')}
            </div>
            <Link to="/admin/drivers">{t('datasources.create.manage_custom_drivers_link')}</Link>
          </div>
        }
        helpText={t('datasources.create.source_uploaded_help')}
      />
    </div>
  );
}

interface TypeGroupProps {
  title: string;
  options: DatasourceTypeOption[];
  selectedKey: string | null;
  onSelect: (option: DatasourceTypeOption) => void;
  emptyState?: React.ReactNode;
  helpText?: string;
}

function TypeGroup({
  title, options, selectedKey, onSelect, emptyState, helpText,
}: TypeGroupProps) {
  const { t } = useTranslation();
  return (
    <section style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <h3 style={{ margin: 0, fontSize: 13, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {title}
        </h3>
        {helpText && (
          <span className="muted" style={{ fontSize: 12 }}>{helpText}</span>
        )}
      </div>
      {options.length === 0 && emptyState}
      {options.length > 0 && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
            gap: 14,
          }}
        >
          {options.map((option) => {
            const key = optionKey(option);
            const selected = selectedKey === key;
            const isUploaded = option.source === 'uploaded';
            const descriptionKey = `datasources.create.type_description.${option.code.toLowerCase()}`;
            return (
              <button
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => onSelect(option)}
                key={key}
                style={{
                  all: 'unset',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 12,
                  padding: 16,
                  borderRadius: 'var(--radius-md)',
                  border: `1px solid ${selected ? 'var(--accent)' : 'var(--border)'}`,
                  background: selected ? 'var(--accent-bg)' : 'var(--bg-elev)',
                  cursor: 'pointer',
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
                      {option.default_port > 0
                        ? t('datasources.create.default_port_label', { port: option.default_port })
                        : option.code}
                    </div>
                  </div>
                </div>
                <div className="muted" style={{ fontSize: 12, lineHeight: 1.4 }}>
                  {isUploaded
                    ? `${t('datasources.create.uploaded_vendor_label')}: ${option.vendor_name ?? '—'} · `
                      + `${t('datasources.create.uploaded_class_label')}: ${option.driver_class ?? '—'}`
                    : t(descriptionKey)}
                </div>
                <div>
                  <DriverStatusBadge
                    status={option.driver_status}
                    bundled={option.bundled}
                    size="sm"
                  />
                </div>
              </button>
            );
          })}
        </div>
      )}
    </section>
  );
}
