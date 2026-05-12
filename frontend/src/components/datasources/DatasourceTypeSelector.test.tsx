import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DatasourceTypeSelector, optionKey } from './DatasourceTypeSelector';
import type { DatasourceTypeOption } from '@/types/api';

const bundledTypes: DatasourceTypeOption[] = [
  {
    code: 'POSTGRESQL',
    display_name: 'PostgreSQL',
    icon_url: '/db-icons/postgresql.svg',
    default_port: 5432,
    default_ssl_mode: 'VERIFY_FULL',
    jdbc_url_template: 'jdbc:postgresql://{host}:{port}/{database_name}',
    driver_status: 'READY',
    bundled: true,
    source: 'bundled',
    custom_driver_id: null,
    vendor_name: null,
    driver_class: null,
  },
  {
    code: 'MYSQL',
    display_name: 'MySQL',
    icon_url: '/db-icons/mysql.svg',
    default_port: 3306,
    default_ssl_mode: 'REQUIRE',
    jdbc_url_template: 'jdbc:mysql://{host}:{port}/{database_name}',
    driver_status: 'AVAILABLE',
    bundled: false,
    source: 'bundled',
    custom_driver_id: null,
    vendor_name: null,
    driver_class: null,
  },
  {
    code: 'ORACLE',
    display_name: 'Oracle Database',
    icon_url: '/db-icons/oracle.svg',
    default_port: 1521,
    default_ssl_mode: 'REQUIRE',
    jdbc_url_template: 'jdbc:oracle:thin:@//{host}:{port}/{database_name}',
    driver_status: 'UNAVAILABLE',
    bundled: false,
    source: 'bundled',
    custom_driver_id: null,
    vendor_name: null,
    driver_class: null,
  },
];

const uploadedDriver: DatasourceTypeOption = {
  code: 'CUSTOM',
  display_name: 'Acme (custom snowflake.jar)',
  icon_url: '/db-icons/custom.svg',
  default_port: 0,
  default_ssl_mode: 'DISABLE',
  jdbc_url_template: '',
  driver_status: 'READY',
  bundled: false,
  source: 'uploaded',
  custom_driver_id: 'driver-1',
  vendor_name: 'Acme',
  driver_class: 'com.acme.JdbcDriver',
};

function wrap(ui: React.ReactNode) {
  return <MemoryRouter>{ui}</MemoryRouter>;
}

describe('DatasourceTypeSelector', () => {
  it('renders one card per type with status badge', () => {
    render(
      wrap(
        <DatasourceTypeSelector
          types={bundledTypes}
          selectedKey={null}
          onSelect={() => {}}
        />,
      ),
    );
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument();
    expect(screen.getByText('MySQL')).toBeInTheDocument();
    expect(screen.getByText('Oracle Database')).toBeInTheDocument();
    expect(screen.getByText('Bundled')).toBeInTheDocument();
    expect(screen.getByText('Will download')).toBeInTheDocument();
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
  });

  it('marks the selected card aria-checked', () => {
    render(
      wrap(
        <DatasourceTypeSelector
          types={bundledTypes}
          selectedKey="bundled:MYSQL"
          onSelect={() => {}}
        />,
      ),
    );
    const mysqlCard = screen.getByText('MySQL').closest('button')!;
    expect(mysqlCard.getAttribute('aria-checked')).toBe('true');
  });

  it('invokes onSelect when an enabled card is clicked', () => {
    const onSelect = vi.fn();
    render(
      wrap(
        <DatasourceTypeSelector
          types={bundledTypes}
          selectedKey={null}
          onSelect={onSelect}
        />,
      ),
    );
    fireEvent.click(screen.getByText('PostgreSQL'));
    expect(onSelect).toHaveBeenCalledWith(bundledTypes[0]);
  });

  it('keeps UNAVAILABLE cards selectable so the user can attempt driver resolution', () => {
    const onSelect = vi.fn();
    render(
      wrap(
        <DatasourceTypeSelector
          types={bundledTypes}
          selectedKey={null}
          onSelect={onSelect}
        />,
      ),
    );
    const oracleCard = screen.getByText('Oracle Database').closest('button')!;
    expect(oracleCard).not.toBeDisabled();
    expect(oracleCard.getAttribute('aria-disabled')).toBeNull();
    fireEvent.click(oracleCard);
    expect(onSelect).toHaveBeenCalledWith(bundledTypes[2]);
  });

  it('groups bundled and uploaded drivers under separate headings', () => {
    render(
      wrap(
        <DatasourceTypeSelector
          types={[...bundledTypes, uploadedDriver]}
          selectedKey={null}
          onSelect={() => {}}
        />,
      ),
    );
    expect(screen.getByRole('heading', { name: 'Bundled drivers' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Custom drivers' })).toBeInTheDocument();
    expect(screen.getByText('Acme (custom snowflake.jar)')).toBeInTheDocument();
  });

  it('shows the upload-deep-link prompt when no uploaded drivers exist yet', () => {
    render(
      wrap(
        <DatasourceTypeSelector
          types={bundledTypes}
          selectedKey={null}
          onSelect={() => {}}
        />,
      ),
    );
    const link = screen.getByRole('link', { name: /manage custom drivers/i });
    expect(link).toBeInTheDocument();
    expect(link.getAttribute('href')).toBe('/admin/drivers');
  });

  it('selects an uploaded driver and passes the option to onSelect', () => {
    const onSelect = vi.fn();
    render(
      wrap(
        <DatasourceTypeSelector
          types={[...bundledTypes, uploadedDriver]}
          selectedKey={null}
          onSelect={onSelect}
        />,
      ),
    );
    fireEvent.click(screen.getByText('Acme (custom snowflake.jar)'));
    expect(onSelect).toHaveBeenCalledWith(uploadedDriver);
  });

  it('optionKey distinguishes uploaded entries with the same target db_type', () => {
    const other = { ...uploadedDriver, custom_driver_id: 'driver-2' };
    expect(optionKey(uploadedDriver)).not.toEqual(optionKey(other));
    expect(optionKey(bundledTypes[0]!)).toBe('bundled:POSTGRESQL');
  });
});
