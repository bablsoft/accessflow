import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DatasourceTypeSelector } from './DatasourceTypeSelector';
import type { DatasourceTypeOption } from '@/types/api';

const types: DatasourceTypeOption[] = [
  {
    code: 'POSTGRESQL',
    display_name: 'PostgreSQL',
    icon_url: '/db-icons/postgresql.svg',
    default_port: 5432,
    default_ssl_mode: 'VERIFY_FULL',
    jdbc_url_template: 'jdbc:postgresql://{host}:{port}/{database_name}',
    driver_status: 'READY',
  },
  {
    code: 'MYSQL',
    display_name: 'MySQL',
    icon_url: '/db-icons/mysql.svg',
    default_port: 3306,
    default_ssl_mode: 'REQUIRE',
    jdbc_url_template: 'jdbc:mysql://{host}:{port}/{database_name}',
    driver_status: 'AVAILABLE',
  },
  {
    code: 'ORACLE',
    display_name: 'Oracle Database',
    icon_url: '/db-icons/generic.svg',
    default_port: 1521,
    default_ssl_mode: 'REQUIRE',
    jdbc_url_template: 'jdbc:oracle:thin:@//{host}:{port}/{database_name}',
    driver_status: 'UNAVAILABLE',
  },
];

describe('DatasourceTypeSelector', () => {
  it('renders one card per type with status badge', () => {
    render(<DatasourceTypeSelector types={types} selectedCode={null} onSelect={() => {}} />);
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument();
    expect(screen.getByText('MySQL')).toBeInTheDocument();
    expect(screen.getByText('Oracle Database')).toBeInTheDocument();
    expect(screen.getByText('Driver ready')).toBeInTheDocument();
    expect(screen.getByText('Will download')).toBeInTheDocument();
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
  });

  it('marks the selected card aria-checked', () => {
    render(<DatasourceTypeSelector types={types} selectedCode="MYSQL" onSelect={() => {}} />);
    const mysqlCard = screen.getByText('MySQL').closest('button')!;
    expect(mysqlCard.getAttribute('aria-checked')).toBe('true');
  });

  it('invokes onSelect when an enabled card is clicked', () => {
    const onSelect = vi.fn();
    render(<DatasourceTypeSelector types={types} selectedCode={null} onSelect={onSelect} />);
    fireEvent.click(screen.getByText('PostgreSQL'));
    expect(onSelect).toHaveBeenCalledWith(types[0]);
  });

  it('disables UNAVAILABLE cards and does not invoke onSelect', () => {
    const onSelect = vi.fn();
    render(<DatasourceTypeSelector types={types} selectedCode={null} onSelect={onSelect} />);
    const oracleCard = screen.getByText('Oracle Database').closest('button')!;
    expect(oracleCard).toBeDisabled();
    fireEvent.click(oracleCard);
    expect(onSelect).not.toHaveBeenCalled();
  });
});
