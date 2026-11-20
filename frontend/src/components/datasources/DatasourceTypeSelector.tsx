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
  if (option.source === 'uploaded' && option.custom_driver_id) {
    return `uploaded:${option.custom_driver_id}`;
  }
  if (option.source === 'connector' && option.connector_id) {
    return `connector:${option.connector_id}`;
  }
  return `bundled:${option.code}`;
}

export function DatasourceTypeSelector({
  types,
  selectedKey,
  onSelect,
}: DatasourceTypeSelectorProps) {
  const { t } = useTranslation();

  const grouped = useMemo(() => {
    const bundled: DatasourceTypeOption[] = [];
    const connectors: DatasourceTypeOption[] = [];
    const uploaded: DatasourceTypeOption[] = [];
    for (const option of types) {
      if (option.source === 'uploaded') uploaded.push(option);
      else if (option.source === 'connector') connectors.push(option);
      else bundled.push(option);
    }
    return { bundled, connectors, uploaded };
  }, [types]);

  return (
    <div role="radiogroup" aria-label={t('datasources.create.select_database')}
      className="u-flex u-col u-gap-5"
    >
      <TypeGroup
        title={t('datasources.create.source_bundled')}
        options={grouped.bundled}
        selectedKey={selectedKey}
        onSelect={onSelect}
      />
      {grouped.connectors.length > 0 && (
        <TypeGroup
          title={t('datasources.create.source_connector')}
          options={grouped.connectors}
          selectedKey={selectedKey}
          onSelect={onSelect}
          helpText={t('datasources.create.source_connector_help')}
        />
      )}
      <TypeGroup
        title={t('datasources.create.source_uploaded')}
        options={grouped.uploaded}
        selectedKey={selectedKey}
        onSelect={onSelect}
        emptyState={
          <div
            className="muted u-text-xs u-p-3 u-rounded-md u-bg-sunken"
            style={{ border: '1px dashed var(--border)' }}
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
    <section className="u-flex u-col" style={{ gap: 10 }}>
      <div className="u-flex u-items-baseline u-gap-2">
        <h3 className="u-m-0 u-text-sm u-uppercase u-tracking-wide">
          {title}
        </h3>
        {helpText && (
          <span className="muted u-text-xs">{helpText}</span>
        )}
      </div>
      {options.length === 0 && emptyState}
      {options.length > 0 && (
        <div
          className="u-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 14 }}
        >
          {options.map((option) => {
            const key = optionKey(option);
            const selected = selectedKey === key;
            const isUploaded = option.source === 'uploaded';
            const isConnector = option.source === 'connector';
            const descriptionKey = `datasources.create.type_description.${option.code.toLowerCase()}`;
            return (
              <button
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => onSelect(option)}
                key={key}
                className="u-flex u-col u-gap-3 u-p-4 u-rounded-md u-pointer" style={{ all: 'unset', border: `1px solid ${selected ? 'var(--accent)' : 'var(--border)'}`, background: selected ? 'var(--accent-bg)' : 'var(--bg-elev)', transition: 'border-color 0.15s, background 0.15s' }}
              >
                <div className="u-flex u-items-center u-gap-3">
                  <img
                    src={option.icon_url}
                    alt=""
                    width={36}
                    height={36}
                    className="u-p-1 u-rounded-md u-bg-sunken" style={{ objectFit: 'contain' }}
                  />
                  <div className="u-flex-1 u-min-w-0">
                    <div className="u-fw-600 u-text-md">{option.display_name}</div>
                    <div className="muted mono u-text-2xs">
                      {option.default_port > 0
                        ? t('datasources.create.default_port_label', { port: option.default_port })
                        : option.code}
                    </div>
                  </div>
                </div>
                <div
                  className="muted u-text-xs"
                  style={{ lineHeight: 1.4, overflowWrap: 'anywhere' }}
                >
                  {isUploaded
                    ? `${t('datasources.create.uploaded_vendor_label')}: ${option.vendor_name ?? '—'} · `
                      + `${t('datasources.create.uploaded_class_label')}: ${option.driver_class ?? '—'}`
                    : isConnector
                      ? option.description ?? option.vendor_name ?? option.display_name
                      : t(descriptionKey, { defaultValue: option.description ?? option.display_name })}
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
